package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.scheduler.Job;
import com.bebebe.agent.scheduler.JobStatus;
import com.bebebe.agent.scheduler.JobStore;
import com.bebebe.agent.scheduler.Repeat;
import com.bebebe.agent.scheduler.SchedulerConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.TestLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderMenuTest {

    private static final String CHAT = "TELEGRAM:1";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Instant SOON = Instant.parse("2026-09-19T14:00:00Z");

    @TempDir
    Path temp;

    private ScriptLibrary library;
    private MemoryStore memory;
    private JobStore jobs;
    private MenuController controller;

    @BeforeEach
    void setUp() {
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        jobs = new JobStore(SchedulerConfig.from(AppConfig.fromToml("""
                [scheduler]
                db_path = "%s"
                """.formatted(temp.resolve("jobs.db"))).section(SchedulerConfig.SECTION)));
        controller = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);
        controller.attachJobs(jobs, () -> ZONE);
    }

    @AfterEach
    void tearDown() {
        jobs.close();
        library.close();
        memory.close();
    }

    private static String keyboard(MenuScreen screen) {
        return screen.keyboard().inlineKeyboard().toString();
    }

    private MenuResponse press(CallbackData data) {
        return controller.handle(data, CHAT);
    }

    private Job add(String summary) {
        return jobs.add(SOON, "Time to remind: " + summary, summary, CHAT, Repeat.ONCE, null);
    }

    @Test
    void rootScreenShowsCountersAndTwoButtons() {
        add("врач");
        Job fired = add("вода");
        jobs.markFired(fired.id(), JobStatus.FIRED);

        MenuScreen screen = controller.screenFor(MenuSection.REMINDERS);

        assertTrue(screen.text().contains("Active: 1"), screen.text());
        assertTrue(screen.text().contains("In history: 1"), screen.text());
        assertTrue(keyboard(screen).contains(CallbackData.remindersActive(0).encode()));
        assertTrue(keyboard(screen).contains(CallbackData.remindersHistory(0).encode()));
    }

    @Test
    void withoutSchedulerSectionExplainsWhy() {
        MenuController bare = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);

        MenuResponse response = bare.handle(CallbackData.remindersActive(0), CHAT);

        assertNull(response.screen());
        assertEquals("Scheduler is off", response.toast());
    }

    @Test
    void emptyActiveListExplainsWhy() {
        MenuScreen screen = press(CallbackData.remindersActive(0)).screen();

        assertTrue(screen.text().contains("Nothing scheduled"), screen.text());
    }

    @Test
    void activeWithPaginationBy6() {
        for (int i = 1; i <= ReminderScreens.PAGE_SIZE + 2; i++) {
            add("дело " + i);
        }

        MenuScreen first = press(CallbackData.remindersActive(0)).screen();
        MenuScreen second = press(CallbackData.remindersActive(1)).screen();

        assertTrue(keyboard(first).contains("1 / 2"), keyboard(first));
        assertTrue(keyboard(first).contains(CallbackData.remindersActive(1).encode()));
        assertTrue(keyboard(second).contains("2 / 2"), keyboard(second));
        assertTrue(keyboard(second).contains("дело 7") || keyboard(second).contains("дело 8"), keyboard(second));
    }

    @Test
    void cardShowsNoteAndCancelButton() {
        Job job = add("врач");

        MenuScreen screen = press(CallbackData.reminderView(job.id(), 0)).screen();

        assertTrue(screen.text().contains("врач"), screen.text());
        assertTrue(screen.text().contains("Time to remind: врач"), screen.text());
        assertTrue(screen.text().contains("Status: "), screen.text());
        assertTrue(keyboard(screen).contains(CallbackData.reminderCancel(job.id(), 0).encode()));
    }

    @Test
    void cancelRemovesFromActiveAndPutsInHistory() {
        Job job = add("врач");

        MenuResponse response = press(CallbackData.reminderCancel(job.id(), 0));

        assertEquals("Cancelled", response.toast());
        assertEquals(0, jobs.countPending());
        assertEquals(JobStatus.CANCELLED, jobs.history().getFirst().status());
        assertTrue(response.screen().text().contains("Nothing scheduled"));
    }

    @Test
    void repeatedCancelDoesNotCrash() {
        Job job = add("врач");
        press(CallbackData.reminderCancel(job.id(), 0));

        MenuResponse response = press(CallbackData.reminderCancel(job.id(), 0));

        assertEquals("Already inactive", response.toast());
    }

    @Test
    void historyCardHasNoCancelButton() {
        Job job = add("вода");
        jobs.markFired(job.id(), JobStatus.MISSED);

        MenuScreen screen = press(CallbackData.reminderView(job.id(), 0)).screen();

        assertFalse(keyboard(screen).contains(CallbackData.reminderCancel(job.id(), 0).encode()));
        assertTrue(keyboard(screen).contains(CallbackData.remindersHistory(0).encode()), "back leads to history");
    }

    @Test
    void historyMarksStatusWithIcon() {
        Job fired = add("сработало");
        Job missed = add("пропущено");
        jobs.markFired(fired.id(), JobStatus.FIRED);
        jobs.markFired(missed.id(), JobStatus.MISSED);

        String keys = keyboard(press(CallbackData.remindersHistory(0)).screen());

        assertTrue(keys.contains("✅"), keys);
        assertTrue(keys.contains("⏰"), keys);
        assertTrue(keys.contains(CallbackData.remindersClearHistory(false).encode()));
    }

    @Test
    void clearingHistoryRequiresConfirmationAndKeepsActive() {
        add("активное");
        Job fired = add("старое");
        jobs.markFired(fired.id(), JobStatus.FIRED);

        MenuScreen confirm = press(CallbackData.remindersClearHistory(false)).screen();
        assertTrue(confirm.text().contains("1 entries"), confirm.text());
        assertEquals(1, jobs.history().size(), "nothing deleted before confirmation");

        MenuResponse done = press(CallbackData.remindersClearHistory(true));

        assertEquals("Deleted: 1", done.toast());
        assertTrue(jobs.history().isEmpty());
        assertEquals(1, jobs.countPending());
    }

    @Test
    void missingIdReturnsToList() {
        MenuResponse response = press(CallbackData.reminderView(999, 0));

        assertEquals("Not found", response.toast());
        assertTrue(response.screen().text().contains("Active"));
    }
}
