package com.bebebe.agent.scheduler;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRunnerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private static final Instant START = ZonedDateTime.of(2026, 9, 19, 12, 0, 0, 0, ZONE).toInstant();

    @TempDir
    Path temp;

    private JobStore store;
    private MutableClock clock;
    private JobRunner runner;

    record Fired(long id, String summary, boolean late) {
    }

    private final List<Fired> fired = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SchedulerConfig config = SchedulerConfig.from(AppConfig.fromToml("""
                [scheduler]
                db_path = "%s"
                tick_seconds = 30
                late_tolerance_minutes = 5
                """.formatted(temp.resolve("jobs.db"))).section(SchedulerConfig.SECTION));
        store = new JobStore(config);
        clock = new MutableClock(START, ZONE);
        runner = new JobRunner(store, config, clock,
                (job, late, now) -> fired.add(new Fired(job.id(), job.summary(), late)));
    }

    @AfterEach
    void tearDown() {
        runner.close();
        store.close();
    }

    private Job in(Duration delay, String summary) {
        return store.add(clock.instant().plus(delay), "prompt: " + summary, summary, "TELEGRAM:1", Repeat.ONCE, "t");
    }

    @Test
    void tickDoesNotFireEarly() {
        in(Duration.ofMinutes(10), "врач");

        runner.tick();

        assertTrue(fired.isEmpty());
        assertEquals(1, store.countPending());
    }

    @Test
    void tickFiresWhenDue() {
        Job job = in(Duration.ofMinutes(10), "врач");

        clock.advance(Duration.ofMinutes(10));
        runner.tick();

        assertEquals(List.of(new Fired(job.id(), "врач", false)), fired);
        assertEquals(JobStatus.FIRED, store.byId(job.id()).orElseThrow().status());
    }

    @Test
    void doesNotFireTwice() {
        in(Duration.ofMinutes(1), "раз");
        clock.advance(Duration.ofMinutes(2));

        runner.tick();
        runner.tick();

        assertEquals(1, fired.size());
    }

    @Test
    void smallDelayIsOnTime() {
        in(Duration.ofMinutes(1), "чуть позже");

        clock.advance(Duration.ofMinutes(4));
        runner.tick();

        assertFalse(fired.getFirst().late());
        assertEquals(JobStatus.FIRED, store.history().getFirst().status());
    }

    @Test
    void bigDelayIsMissed() {

        in(Duration.ofMinutes(10), "проспали");

        clock.advance(Duration.ofHours(1));
        runner.tick();

        assertTrue(fired.getFirst().late(), "an hour late is catch-up, not on time");
        assertEquals(JobStatus.MISSED, store.history().getFirst().status());
    }

    @Test
    void catchUpHandlesOverdueWhileAgentWasDown() {
        in(Duration.ofMinutes(5), "первое");
        in(Duration.ofMinutes(30), "второе");
        in(Duration.ofHours(5), "будущее");

        clock.advance(Duration.ofHours(2));
        int caught = runner.catchUp();

        assertEquals(2, caught);
        assertEquals(List.of("первое", "второе"), fired.stream().map(Fired::summary).toList());
        assertTrue(fired.stream().allMatch(Fired::late));
        assertEquals(1, store.countPending(), "the future one must stay pending");
    }

    @Test
    void catchUpWithoutOverdueDoesNothing() {
        in(Duration.ofHours(1), "потом");

        assertEquals(0, runner.catchUp());
        assertTrue(fired.isEmpty());
    }

    @Test
    void catchUpDistinguishesSlightlyLateAndOverdue() {

        Job soon = in(Duration.ofMinutes(118), "почти вовремя");
        Job old = in(Duration.ofMinutes(1), "давно");

        clock.advance(Duration.ofHours(2));
        runner.catchUp();

        assertEquals(2, fired.size());
        assertFalse(fired.stream().filter(f -> f.id() == soon.id()).findFirst().orElseThrow().late());
        assertTrue(fired.stream().filter(f -> f.id() == old.id()).findFirst().orElseThrow().late());
        assertEquals(JobStatus.FIRED, store.byId(soon.id()).orElseThrow().status());
        assertEquals(JobStatus.MISSED, store.byId(old.id()).orElseThrow().status());
    }

    @Test
    void tickRightAfterCatchUpDoesNotFireSameAgain() {

        in(Duration.ofMinutes(1), "одно");
        clock.advance(Duration.ofHours(1));

        runner.catchUp();
        runner.tick();
        runner.tick();

        assertEquals(1, fired.size());
        assertEquals(0, store.countPending());
    }

    @Test
    void catchUpGivesHandlerTimeOfCatchUpNotFiring() {
        List<Instant> nows = new ArrayList<>();
        JobRunner watching = new JobRunner(store, store.config(), clock, (job, late, now) -> nows.add(now));
        in(Duration.ofMinutes(1), "срок");
        clock.advance(Duration.ofHours(3));

        watching.catchUp();

        assertEquals(List.of(clock.instant()), nows, "the model gets 'now' to say honestly how late it is");
        watching.close();
    }

    @Test
    void cancelledIsNotCaughtUpAfterIdle() {
        Job job = in(Duration.ofMinutes(1), "отменено");
        assertTrue(store.cancel(job.id()));

        clock.advance(Duration.ofHours(2));

        assertEquals(0, runner.catchUp());
        assertTrue(fired.isEmpty());
        assertEquals(JobStatus.CANCELLED, store.byId(job.id()).orElseThrow().status());
    }

    @Test
    void rescheduleKeepsIdAndFiresAtNewTime() {
        Job job = in(Duration.ofMinutes(1), "перенос");
        Instant later = clock.instant().plus(Duration.ofMinutes(30));

        assertTrue(store.reschedule(job.id(), later));
        clock.advance(Duration.ofMinutes(2));
        runner.tick();
        assertTrue(fired.isEmpty(), "must not fire at the old time");

        clock.advance(Duration.ofMinutes(29));
        runner.tick();
        assertEquals(1, fired.size());
        assertEquals(job.id(), fired.getFirst().id(), "same id -- links to list items survive");
        assertFalse(fired.getFirst().late());
    }

    @Test
    void firedAndCancelledCannotBeRescheduled() {
        Job done = in(Duration.ofMinutes(1), "готово");
        clock.advance(Duration.ofMinutes(2));
        runner.tick();
        Job cancelled = in(Duration.ofMinutes(10), "отмена");
        store.cancel(cancelled.id());

        assertFalse(store.reschedule(done.id(), clock.instant().plus(Duration.ofHours(1))));
        assertFalse(store.reschedule(cancelled.id(), clock.instant().plus(Duration.ofHours(1))));
        assertEquals(0, store.countPending());
    }

    @Test
    void repeatingJobIsCaughtUpOnceAndMovedForward() {

        Job daily = store.add(clock.instant().plus(Duration.ofMinutes(1)), "таблетки", "таблетки",
                "TELEGRAM:1", Repeat.daily(), "t");
        clock.advance(Duration.ofDays(2).plusHours(3));

        assertEquals(1, runner.catchUp());
        assertTrue(fired.getFirst().late());

        List<Job> pending = store.pending();
        assertEquals(1, pending.size());
        ZonedDateTime next = ZonedDateTime.ofInstant(pending.getFirst().fireAt(), ZONE);
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZONE);
        assertEquals(now.toLocalDate().plusDays(1), next.toLocalDate());
        assertEquals(ZonedDateTime.ofInstant(daily.fireAt(), ZONE).toLocalTime(), next.toLocalTime());
        assertEquals(JobStatus.MISSED, store.byId(daily.id()).orElseThrow().status());
    }

    @Test
    void repeatedStartAndStopAreSafe() {
        runner.start();
        runner.start();
        assertTrue(runner.isRunning());

        runner.stop();
        runner.stop();
        assertFalse(runner.isRunning());

        runner.start();
        assertTrue(runner.isRunning(), "can be started again after stop");
    }

    @Test
    void dailyIsScheduledForTomorrowSameTime() {
        Job daily = store.add(clock.instant().plus(Duration.ofMinutes(1)), "зарядка", "зарядка",
                "TELEGRAM:1", Repeat.daily(), "t");

        clock.advance(Duration.ofMinutes(2));
        runner.tick();

        List<Job> pending = store.pending();
        assertEquals(1, pending.size());
        Job next = pending.getFirst();
        assertEquals(daily.fireAt().plus(Duration.ofDays(1)), next.fireAt());
        assertEquals("зарядка", next.summary());
        assertEquals(Repeat.Kind.DAILY, next.repeat().kind());
        assertEquals(JobStatus.FIRED, store.byId(daily.id()).orElseThrow().status(), "the old one goes to history");
    }

    @Test
    void weeklyPicksNearestMatchingDay() {

        Repeat monWed = Repeat.weekly(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));
        store.add(clock.instant().plus(Duration.ofMinutes(1)), "созвон", "созвон", "TELEGRAM:1", monWed, "t");

        clock.advance(Duration.ofMinutes(2));
        runner.tick();

        Job next = store.pending().getFirst();
        assertEquals(DayOfWeek.MONDAY, ZonedDateTime.ofInstant(next.fireAt(), ZONE).getDayOfWeek());
        assertEquals(12, ZonedDateTime.ofInstant(next.fireAt(), ZONE).getHour());
    }

    @Test
    void missedRepeatsAreNotBackfilled() {

        store.add(clock.instant().plus(Duration.ofMinutes(1)), "ежедневное", "ежедневное",
                "TELEGRAM:1", Repeat.daily(), "t");

        clock.advance(Duration.ofDays(7));
        runner.catchUp();

        List<Job> pending = store.pending();
        assertEquals(1, pending.size());
        assertTrue(pending.getFirst().fireAt().isAfter(clock.instant()));
    }

    @Test
    void onceDoesNotRepeat() {
        in(Duration.ofMinutes(1), "разово");
        clock.advance(Duration.ofMinutes(2));

        runner.tick();

        assertEquals(0, store.countPending());
    }

    @Test
    void cancelledDoesNotFire() {
        Job job = in(Duration.ofMinutes(1), "отменю");
        assertTrue(store.cancel(job.id()));
        assertFalse(store.cancel(job.id()), "repeated cancel -- nothing left");

        clock.advance(Duration.ofMinutes(2));
        runner.tick();

        assertTrue(fired.isEmpty());
        assertEquals(JobStatus.CANCELLED, store.byId(job.id()).orElseThrow().status());
    }

    @Test
    void failingHandlerDoesNotBreakTickOrRepeatJob() {
        JobRunner broken = new JobRunner(store, SchedulerConfig.from(AppConfig.fromToml(
                "[scheduler]\ndb_path = \"" + temp.resolve("jobs.db") + "\"").section(SchedulerConfig.SECTION)),
                clock, (job, late, now) -> { throw new IllegalStateException("boom"); });
        in(Duration.ofMinutes(1), "хрупкое");
        clock.advance(Duration.ofMinutes(2));

        broken.tick();
        broken.tick();

        assertEquals(0, store.countPending(), "the job is marked before the handler is called -- no second firing");
    }

    @Test
    void historyNewestFirst() {
        Job a = in(Duration.ofMinutes(1), "a");
        Job b = in(Duration.ofMinutes(2), "b");
        clock.advance(Duration.ofMinutes(3));
        runner.tick();

        List<Job> history = store.history();
        assertEquals(2, history.size());
        assertEquals(b.id(), history.getFirst().id());
        assertEquals(a.id(), history.getLast().id());
    }

    @Test
    void repeatIsParsedAndSerialised() {
        assertEquals(Repeat.Kind.DAILY, Repeat.fromWire("daily").kind());
        assertEquals(Repeat.Kind.DAILY, Repeat.fromWire("ежедневно").kind());
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), Repeat.fromWire("weekly:mon,fri").days());
        assertEquals(Set.of(DayOfWeek.TUESDAY), Repeat.fromWire("weekly:вт").days());
        assertEquals(Repeat.ONCE, Repeat.fromWire("мусор"));
        assertEquals(Repeat.ONCE, Repeat.fromWire(""));

        assertEquals("weekly:MON,FRI", Repeat.weekly(Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)).toWire());
        assertEquals(Repeat.weekly(Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)), Repeat.fromWire("weekly:MON,FRI"));
    }

    @Test
    void notifySendCommandWithoutShell() {
        DesktopNotifier notifier = new DesktopNotifier(true, Path.of("/usr/bin/notify-send"));

        List<String> command = notifier.command("Напоминание", "текст; rm -rf /");

        assertEquals("/usr/bin/notify-send", command.getFirst());
        assertEquals("текст; rm -rf /", command.getLast(), "the text is a separate argument, not a command");
        assertFalse(new DesktopNotifier(true, null).isAvailable());
        assertFalse(new DesktopNotifier(false, Path.of("/usr/bin/notify-send")).isAvailable());
    }
}
