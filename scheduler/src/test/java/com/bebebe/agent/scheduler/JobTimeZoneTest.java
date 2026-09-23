package com.bebebe.agent.scheduler;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server may stand in a different zone than the person it reminds, so every time here is
 * checked twice: as an absolute instant (what the scheduler compares) and as the wall clock the
 * user would read.
 */
class JobTimeZoneTest {

    /** Where the process runs -- deliberately not where the user is, and with no DST. */
    private static final ZoneId SERVER = ZoneId.of("UTC");

    /** Where the user is: three hours ahead, no DST since 2014. */
    private static final ZoneId USER = ZoneId.of("Europe/Moscow");

    /** A user zone that does change its offset twice a year. */
    private static final ZoneId DST_USER = ZoneId.of("Europe/Berlin");

    @TempDir
    Path temp;

    private JobStore store;
    private JobRunner runner;
    private final List<Long> fired = new ArrayList<>();

    private JobRunner runner(MutableClock clock) {
        SchedulerConfig config = SchedulerConfig.from(AppConfig.fromToml("""
                [scheduler]
                db_path = "%s"
                tick_seconds = 30
                late_tolerance_minutes = 5
                """.formatted(temp.resolve("jobs.db"))).section(SchedulerConfig.SECTION));
        store = new JobStore(config);
        runner = new JobRunner(store, config, clock, (job, late, now) -> fired.add(job.id()));
        return runner;
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void jobSetForFivePmUserTimeFiresAtTwoPmOnTheServer() {

        Instant fiveInTheEvening = ZonedDateTime.of(2026, 9, 19, 17, 0, 0, 0, USER).toInstant();
        MutableClock clock = new MutableClock(
                ZonedDateTime.of(2026, 9, 19, 15, 0, 0, 0, USER).toInstant(), USER);
        JobRunner running = runner(clock);
        Job job = store.add(fiveInTheEvening, "remind about the doctor", "doctor", "TELEGRAM:1",
                Repeat.ONCE, "trace");

        assertEquals("2026-09-19T14:00:00Z", job.fireAt().toString(),
                "stored as an absolute instant, not as the server's wall clock");

        clock.set(ZonedDateTime.of(2026, 9, 19, 16, 59, 0, 0, USER).toInstant());
        running.tick();
        assertTrue(fired.isEmpty(), "a minute early is not yet due");

        clock.set(fiveInTheEvening);
        running.tick();

        assertEquals(List.of(job.id()), fired);
        assertEquals(17, ZonedDateTime.ofInstant(job.fireAt(), USER).getHour(), "17:00 for the user");
        assertEquals(14, ZonedDateTime.ofInstant(job.fireAt(), SERVER).getHour(), "14:00 on the server");
    }

    @Test
    void dailyRepeatKeepsTheUsersWallClockAcrossADstChange() {

        // Sunday 25 October 2026 is when Berlin goes back from +02:00 to +01:00.
        Instant lastSummerMorning = ZonedDateTime.of(2026, 10, 24, 9, 0, 0, 0, DST_USER).toInstant();
        MutableClock clock = new MutableClock(lastSummerMorning, DST_USER);
        JobRunner running = runner(clock);
        Job job = store.add(lastSummerMorning, "morning exercise", "exercise", "TELEGRAM:1",
                Repeat.daily(), "trace");
        assertEquals("+02:00", ZonedDateTime.ofInstant(job.fireAt(), DST_USER).getOffset().getId());

        running.tick();

        Job next = store.pending().getFirst();
        ZonedDateTime forTheUser = ZonedDateTime.ofInstant(next.fireAt(), DST_USER);
        assertEquals(9, forTheUser.getHour(),
                "the day after the clocks change it must still be 9 in the morning, not 8");
        assertEquals(25, forTheUser.getDayOfMonth());
        assertEquals("+01:00", forTheUser.getOffset().getId(), "and it is the winter offset now");

        assertEquals("2026-10-25T08:00:00Z", next.fireAt().toString(),
                "which is an hour later in UTC than the day before -- that is the whole point");
    }

    @Test
    void catchUpComparesInstantsNotLocalTimes() {

        Instant missed = ZonedDateTime.of(2026, 9, 19, 17, 0, 0, 0, USER).toInstant();
        MutableClock clock = new MutableClock(missed.plus(Duration.ofHours(3)), USER);
        JobRunner running = runner(clock);
        store.add(missed, "missed one", "missed", "TELEGRAM:1", Repeat.ONCE, "trace");

        assertEquals(1, running.catchUp());
        assertEquals(JobStatus.MISSED, store.history().getFirst().status(),
                "three hours late is late in any zone");
    }
}
