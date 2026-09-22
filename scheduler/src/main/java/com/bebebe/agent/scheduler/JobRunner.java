package com.bebebe.agent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JobRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    @FunctionalInterface
    public interface Handler {

        void fire(Job job, boolean late, Instant now);
    }

    private final JobStore store;
    private final SchedulerConfig config;
    private final Clock clock;
    private final Handler handler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastTick = new AtomicReference<>();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> ticking;

    public JobRunner(JobStore store, SchedulerConfig config, Handler handler) {
        this(store, config, Clock.systemDefaultZone(), handler);
    }

    public JobRunner(JobStore store, SchedulerConfig config, Clock clock, Handler handler) {
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.handler = handler;
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-tick");
            t.setDaemon(true);
            return t;
        });
        lastTick.set(clock.instant());
        long seconds = config.tick().toSeconds();
        ticking = executor.scheduleAtFixedRate(this::tickSafely, seconds, seconds, TimeUnit.SECONDS);
        log.info("Scheduler started: tick {} s, late tolerance {} min",
                seconds, config.lateTolerance().toMinutes());
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (ticking != null) {
            ticking.cancel(false);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("Scheduler stopped");
    }

    @Override
    public void close() {
        stop();
    }

    public int catchUp() {
        Instant now = clock.instant();
        List<Job> due = store.due(now);
        if (due.isEmpty()) {
            log.debug("Nothing to catch up");
            return 0;
        }
        log.atInfo()
                .addKeyValue("event", "scheduler.catchup")
                .addKeyValue("count", due.size())
                .log("Missed during idle: {} jobs", due.size());
        for (Job job : due) {
            fire(job, isLate(job, now), now);
        }
        return due.size();
    }

    public void tick() {
        Instant now = clock.instant();
        Instant previous = lastTick.getAndSet(now);

        boolean gap = previous != null
                && Duration.between(previous, now).compareTo(config.tick().multipliedBy(4)) > 0;
        if (gap) {
            log.atWarn()
                    .addKeyValue("event", "scheduler.gap")
                    .addKeyValue("gap_seconds", Duration.between(previous, now).toSeconds())
                    .log("Time jump between ticks: {} s -- the machine was probably asleep",
                            Duration.between(previous, now).toSeconds());
        }

        for (Job job : store.due(now)) {
            fire(job, isLate(job, now), now);
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("Scheduler tick failed", e);
        }
    }

    private boolean isLate(Job job, Instant now) {
        return Duration.between(job.fireAt(), now).compareTo(config.lateTolerance()) > 0;
    }

    private void fire(Job job, boolean late, Instant now) {

        store.markFired(job.id(), late ? JobStatus.MISSED : JobStatus.FIRED);
        log.atInfo()
                .addKeyValue("event", "job.fire")
                .addKeyValue("job_id", job.id())
                .addKeyValue("late", late)
                .addKeyValue("late_by_seconds", Duration.between(job.fireAt(), now).toSeconds())
                .log("Job {} «{}»: {}", job.id(), job.summary(), late ? "MISSED, catching up" : "firing");

        scheduleNext(job);

        try {
            handler.fire(job, late, now);
        } catch (RuntimeException e) {
            log.error("Handler of job {} failed", job.id(), e);
        }
    }

    private void scheduleNext(Job job) {
        if (!job.repeat().isRecurring()) {
            return;
        }
        Instant now = clock.instant();
        ZonedDateTime cursor = ZonedDateTime.ofInstant(job.fireAt(), clock.getZone());
        Optional<ZonedDateTime> next = job.repeat().next(cursor);

        int guard = 0;
        while (next.isPresent() && !next.get().toInstant().isAfter(now) && guard++ < 400) {
            next = job.repeat().next(next.get());
        }
        next.ifPresent(when -> {
            Job created = store.add(when.toInstant(), job.prompt(), job.summary(),
                    job.conversationKey(), job.repeat(), job.traceId());
            log.info("Repeat of job {} -> {} at {}", job.id(), created.id(), when);
        });
    }
}
