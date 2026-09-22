package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.ToolResult;
import com.bebebe.agent.watchdog.HeartbeatSource;
import com.bebebe.agent.watchdog.Watchdog;
import com.bebebe.agent.watchdog.WatchdogConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchdogFlowTest {

    private static final WatchdogConfig CONFIG = new WatchdogConfig(true, Duration.ofSeconds(10), Duration.ofSeconds(90));

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ScriptLibrary library;
    private MemoryStore memory;
    private AgentCore core;
    private final CountDownLatch hangStarted = new CountDownLatch(1);
    private final List<String> alerts = new CopyOnWriteArrayList<>();

    private final Tool hanging = new Tool() {
        public String name() { return "hang"; }
        public String description() { return "hangs"; }
        public Map<String, Object> parameters() { return Map.of(); }
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            hangStarted.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("interrupted");
            }
            return ToolResult.ok("woke up");
        }
    };

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
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 60
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
        core = new AgentCore(agentSwitch, ollama, scripts, library, memory, new ToolRegistry().register(hanging),
                null, Clock.systemDefaultZone(), 15);
    }

    @AfterEach
    void tearDown() {
        core.worker().close();
        memory.close();
        library.close();
        ollama.close();
        stub.close();
    }

    private Watchdog watchdog() {
        return new Watchdog(CONFIG, core.activity(), core.worker(),
                (conv, text) -> alerts.add(conv.orElse("-") + " | " + text));
    }

    private static Instant lastBeat(HeartbeatSource source) {
        return source.inFlight().orElseThrow().lastBeat();
    }

    @Test
    void confirmedHangRestartsThreadAndNotifiesConversation() throws Exception {
        stub.enqueue("""
                {"type":"tool_call","tool_name":"hang","arguments":{}}""");
        CompletableFuture<AgentReply> reply = CompletableFuture.supplyAsync(
                () -> core.handle(UserMessage.telegram("зависни", 1L)));
        assertTrue(hangStarted.await(10, TimeUnit.SECONDS), "the tool must start hanging");
        Watchdog watchdog = watchdog();
        Instant beat = lastBeat(core.activity());
        assertTrue(core.activity().inFlight().orElseThrow().busy().isEmpty(), "the tool gives no busy signal");

        assertFalse(watchdog.check(beat.plusSeconds(30)), "too early");
        assertTrue(watchdog.check(beat.plusSeconds(120)), "90 s of silence without a signal -- a hang");

        assertInstanceOf(AgentReply.Silence.class, reply.get(10, TimeUnit.SECONDS), "the hung request is closed silently");
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().startsWith("TELEGRAM:1 | 🐶"), alerts.getFirst());
        assertTrue(core.activity().inFlight().isEmpty(), "idle after restart");

        stub.enqueueReply("живой");
        assertEquals("живой", core.handle(UserMessage.telegram("ты тут?", 1L)).asPlainText());
        assertEquals(1, core.worker().generation());
    }

    @Test
    void busyWithModelIsNotConsideredHung() {

        ActivityMonitor activity = core.activity();
        activity.begin("question", "TELEGRAM:1");
        try (AutoCloseable busy = activity.busy("model reply", Duration.ofSeconds(35))) {
            Instant beat = lastBeat(activity);
            Watchdog watchdog = watchdog();
            assertFalse(watchdog.check(beat.plusSeconds(20)), "within the model timeout");

            assertFalse(watchdog.check(beat.plusSeconds(60)));
            assertTrue(watchdog.check(beat.plusSeconds(120)), "signal expired and silence past the threshold");
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            activity.end();
        }
    }

    @Test
    void scriptUnderBusySignalWithTimeoutPlusMargin() throws Exception {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":"import time\\ntime.sleep(3)\\nprint('ok')",
                 "script_name":"Долгий","explanation":"спит","script_tags":["тест"]}""");
        AgentReply.NeedsConfirmation confirmation = (AgentReply.NeedsConfirmation)
                core.handle(UserMessage.telegram("поспи", 1L));
        stub.enqueuePlain("Поспал.");
        CompletableFuture<AgentReply> reply = CompletableFuture.supplyAsync(() -> core.confirm(confirmation.token()));

        HeartbeatSource.Busy busy = null;
        for (int i = 0; i < 300 && busy == null; i++) {
            busy = core.activity().inFlight().flatMap(f -> f.busy()).filter(b -> b.reason().startsWith("script")).orElse(null);
            Thread.sleep(100);
        }
        assertTrue(busy != null, "a 'script ...' busy signal must be raised during the script");
        Instant beat = lastBeat(core.activity());
        assertTrue(busy.until().isAfter(beat.plusSeconds(60 + 60)), "script timeout 60 s plus pip margin");
        assertFalse(watchdog().check(beat.plusSeconds(100)), "the script runs within its own timeout -- not a hang");

        assertEquals("Поспал.", reply.get(120, TimeUnit.SECONDS).asPlainText());
    }

    @Test
    void stoppingWaitsForShortScriptAndReturnsItsReply() throws Exception {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":"import time\\ntime.sleep(2)\\nprint('успел')",
                 "script_name":"Короткий","explanation":"спит 2 с","script_tags":["тест"]}""");
        AgentReply.NeedsConfirmation confirmation = (AgentReply.NeedsConfirmation)
                core.handle(UserMessage.telegram("сделай", 1L));
        stub.enqueuePlain("Готово: успел.");
        core.setStopGrace(Duration.ofSeconds(60));
        CompletableFuture<AgentReply> reply = CompletableFuture.supplyAsync(() -> core.confirm(confirmation.token()));
        waitUntilScriptRuns();

        long started = System.nanoTime();
        agentSwitch.turnOff();
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals("Готово: успел.", reply.get(30, TimeUnit.SECONDS).asPlainText(), "the finished script's reply arrived");
        assertTrue(waitedMs >= 1000, "stopping waited for the script instead of killing it: " + waitedMs + " мс");
        assertFalse(agentSwitch.isOn());
    }

    @Test
    void stoppingInterruptsLongScriptAfterGracePeriodNotImmediately() throws Exception {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":"import time\\ntime.sleep(50)\\nprint('не дождётесь')",
                 "script_name":"Долгий","explanation":"спит 50 с","script_tags":["тест"]}""");
        AgentReply.NeedsConfirmation confirmation = (AgentReply.NeedsConfirmation)
                core.handle(UserMessage.telegram("сделай", 1L));
        core.setStopGrace(Duration.ofSeconds(2));
        CompletableFuture<AgentReply> reply = CompletableFuture.supplyAsync(() -> core.confirm(confirmation.token()));
        waitUntilScriptRuns();

        long started = System.nanoTime();
        agentSwitch.turnOff();
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(waitedMs >= 2000 && waitedMs < 20_000, "waited the 2 s grace and interrupted: " + waitedMs + " мс");
        AgentReply result = reply.get(15, TimeUnit.SECONDS);
        assertFalse(result.asPlainText().contains("не дождётесь"), "the script did not finish: " + result.asPlainText());

        for (int i = 0; i < 50 && core.activity().inFlight().isPresent(); i++) {
            Thread.sleep(100);
        }
        assertTrue(core.activity().inFlight().isEmpty());
    }

    private void waitUntilScriptRuns() throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            if (core.activity().inFlight().flatMap(f -> f.busy()).filter(b -> b.reason().startsWith("script")).isPresent()) {
                Thread.sleep(300);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the script never started");
    }
}
