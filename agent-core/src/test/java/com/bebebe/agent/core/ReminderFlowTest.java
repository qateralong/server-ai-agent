package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.scheduler.Job;
import com.bebebe.agent.scheduler.JobStatus;
import com.bebebe.agent.scheduler.JobStore;
import com.bebebe.agent.scheduler.SchedulerConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderFlowTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Instant START = ZonedDateTime.of(2026, 9, 19, 15, 0, 0, 0, ZONE).toInstant();

    private static final class MutableClock extends Clock {
        Instant now = START;
        public ZoneId getZone() { return ZONE; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ScriptLibrary library;
    private MemoryStore memory;
    private JobStore jobs;
    private final MutableClock clock = new MutableClock();
    private final List<AgentCore.Outbound> outbound = new CopyOnWriteArrayList<>();
    private AgentCore core;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        agentSwitch = new AgentSwitch(true);
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("lib.db"), temp.resolve("lib"))).section(LibraryConfig.SECTION)));
        memory = TestMemory.inDirectory(temp, 1000);
        jobs = new JobStore(SchedulerConfig.from(AppConfig.fromToml("""
                [scheduler]
                db_path = "%s"
                tick_seconds = 30
                late_tolerance_minutes = 5
                desktop_notifications = false
                """.formatted(temp.resolve("jobs.db"))).section(SchedulerConfig.SECTION)));
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
        core = new AgentCore(agentSwitch, ollama, scripts, library, memory, new ToolRegistry(), jobs, clock, 15);
        core.setNotifier(outbound::add);
    }

    @AfterEach
    void tearDown() {
        agentSwitch.turnOff();
        jobs.close();
        memory.close();
        library.close();
        ollama.close();
        stub.close();
    }

    private static UserMessage ask(String text) {
        return UserMessage.telegram(text, 42L);
    }

    private void enqueueSetReminder(String isoWhen, String prompt, String summary, String repeat) {
        stub.enqueue("""
                {"type":"tool_call","tool_name":"set_reminder","arguments":
                  {"fire_at":"%s","prompt":"%s","summary":"%s","repeat":"%s"}}"""
                .formatted(isoWhen, prompt, summary, repeat));
    }

    @Test
    void toolCreatesJobAndModelAnswersUser() {
        enqueueSetReminder("2026-09-19T17:00:00+03:00", "Пора напомнить про врача", "врач", "");
        stub.enqueuePlain("Понял, напомню в 17:00 сходить к врачу.");

        AgentReply reply = core.handle(ask("напомни через 2 часа сходить к врачу"));

        assertEquals("Понял, напомню в 17:00 сходить к врачу.", reply.asPlainText());
        List<Job> pending = jobs.pending();
        assertEquals(1, pending.size());
        Job job = pending.getFirst();
        assertEquals("врач", job.summary());
        assertEquals("Пора напомнить про врача", job.prompt());
        assertEquals("TELEGRAM:42", job.conversationKey(), "the reminder must return to the same chat");
        assertEquals(START.plus(Duration.ofHours(2)), job.fireAt());

        assertTrue(stub.requests().getFirst().get("messages").get(0).get("content").asText().contains("Now:"));
    }

    @Test
    void momentInPastIsRejectedAndModelSeesError() {
        enqueueSetReminder("2026-09-19T14:00:00+03:00", "x", "x", "");
        stub.enqueuePlain("Это время уже прошло.");

        core.handle(ask("напомни в 14:00"));

        assertEquals(0, jobs.countPending());
        assertTrue(stub.requests().get(1).toString().contains("in the past"));
    }

    @Test
    void firingGoesToCoreAsSystemMessageAndReturnsToSameChat() {
        enqueueSetReminder("2026-09-19T15:10:00+03:00", "Пора напомнить выпить воды", "вода", "");
        stub.enqueuePlain("Напомню в 15:10.");
        core.handle(ask("напомни через 10 минут выпить воды"));

        stub.enqueueReply("Пора выпить воды! 💧");
        clock.advance(Duration.ofMinutes(11));
        core.reminders().runner().tick();

        assertEquals(1, outbound.size(), "the reminder reply must go out");
        assertEquals("TELEGRAM:42", outbound.getFirst().conversationKey());
        assertEquals("Пора выпить воды! 💧", outbound.getFirst().reply().asPlainText());

        String injection = stub.requests().getLast().toString();
        assertTrue(injection.contains("Пора напомнить выпить воды"), injection);
        assertTrue(injection.contains("System message from the scheduler"));
        assertEquals(JobStatus.FIRED, jobs.history().getFirst().status());
    }

    @Test
    void stoppingStopsTickAndStartingStartsIt() {
        enqueueSetReminder("2026-09-19T15:10:00+03:00", "p", "s", "");
        stub.enqueuePlain("ок");
        core.handle(ask("напомни"));
        assertTrue(core.reminders().runner().isRunning(), "ticker runs while the agent is on");

        agentSwitch.turnOff();
        assertTrue(!core.reminders().runner().isRunning(), "onBeforeStop stops the ticker");
        int callsBefore = stub.callCount();
        clock.advance(Duration.ofMinutes(11));

        core.reminders().runner().tick();
        assertEquals(callsBefore, stub.callCount());
        assertTrue(outbound.isEmpty());
    }

    @Test
    void startingCatchesUpMissedWithApologyPrompt() {
        enqueueSetReminder("2026-09-19T15:10:00+03:00", "Пора напомнить про звонок маме", "мама", "");
        stub.enqueuePlain("Напомню.");
        core.handle(ask("напомни через 10 минут позвонить маме"));

        agentSwitch.turnOff();
        clock.advance(Duration.ofHours(3));
        stub.enqueueReply("Прости, опоздал: три часа назад надо было позвонить маме.");

        agentSwitch.turnOn();

        assertEquals(1, outbound.size());
        String injection = stub.requests().getLast().toString();
        assertTrue(injection.contains("did not"), injection);
        assertTrue(injection.contains("should have fired"), injection);
        assertTrue(injection.contains("15:10"), injection);
        assertEquals(JobStatus.MISSED, jobs.history().getFirst().status());
    }

    @Test
    void timeJumpBetweenTicksCountsAsMissed() {

        enqueueSetReminder("2026-09-19T15:10:00+03:00", "p", "s", "");
        stub.enqueuePlain("ок");
        core.handle(ask("напомни"));

        core.reminders().runner().tick();
        clock.advance(Duration.ofHours(1));
        stub.enqueueReply("Извини, проспал напоминание.");
        core.reminders().runner().tick();

        assertEquals(JobStatus.MISSED, jobs.history().getFirst().status());
        assertTrue(stub.requests().getLast().toString().contains("should have fired"));
    }

    @Test
    void dailyIsRescheduledAfterFiring() {
        enqueueSetReminder("2026-09-19T15:05:00+03:00", "зарядка", "зарядка", "daily");
        stub.enqueuePlain("ок");
        core.handle(ask("напоминай каждый день"));

        stub.enqueueReply("Зарядка!");
        clock.advance(Duration.ofMinutes(6));
        core.reminders().runner().tick();

        assertEquals(1, jobs.countPending());
        assertEquals(START.plus(Duration.ofMinutes(5)).plus(Duration.ofDays(1)), jobs.pending().getFirst().fireAt());
    }

    @Test
    void activeRemindersAreVisibleToModelAndCancelledByWords() {
        enqueueSetReminder("2026-09-19T17:00:00+03:00", "Пора напомнить про врача", "врач", "");
        stub.enqueuePlain("Напомню.");
        core.handle(ask("напомни в 17:00 про врача"));
        long id = jobs.pending().getFirst().id();

        stub.enqueue("""
                {"type":"tool_call","tool_name":"manage_reminders","arguments":{"action":"cancel","job_id":%d}}"""
                .formatted(id));
        stub.enqueuePlain("Отменил напоминание про врача.");
        AgentReply reply = core.handle(ask("отмени напоминание про врача"));

        String system = stub.requests().get(2).get("messages").get(0).get("content").asText();
        assertTrue(system.contains("Active reminders"), system);
        assertTrue(system.contains("#" + id + " — 19 September 2026, 17:00 — врач"), system);
        assertEquals("Отменил напоминание про врача.", reply.asPlainText());
        assertEquals(0, jobs.countPending());
        assertEquals(JobStatus.CANCELLED, jobs.history().getFirst().status());
    }

    @Test
    void rescheduleKeepsJobId() {
        enqueueSetReminder("2026-09-19T17:00:00+03:00", "p", "врач", "");
        stub.enqueuePlain("ок");
        core.handle(ask("напомни"));
        long id = jobs.pending().getFirst().id();

        stub.enqueue("""
                {"type":"tool_call","tool_name":"manage_reminders","arguments":
                  {"action":"reschedule","job_id":%d,"fire_at":"2026-09-19T18:00:00+03:00"}}""".formatted(id));
        stub.enqueuePlain("Перенёс на 18:00.");
        core.handle(ask("перенеси на час позже"));

        assertEquals(id, jobs.pending().getFirst().id());
        assertEquals(START.plus(Duration.ofHours(3)), jobs.pending().getFirst().fireAt());
    }
}
