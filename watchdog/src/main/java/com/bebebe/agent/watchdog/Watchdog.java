package com.bebebe.agent.watchdog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class Watchdog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    private final WatchdogConfig config;
    private final HeartbeatSource source;
    private final Restartable target;
    private final BiConsumer<Optional<String>, String> alerts;
    private final Clock clock;
    private final AtomicInteger restarts = new AtomicInteger();
    private ScheduledExecutorService executor;

    public Watchdog(WatchdogConfig config, HeartbeatSource source, Restartable target,
                    BiConsumer<Optional<String>, String> alerts) {
        this(config, source, target, alerts, Clock.systemUTC());
    }

    public Watchdog(WatchdogConfig config, HeartbeatSource source, Restartable target,
                    BiConsumer<Optional<String>, String> alerts, Clock clock) {
        this.config = config;
        this.source = source;
        this.target = target;
        this.alerts = alerts;
        this.clock = clock;
    }

    public int restarts() {
        return restarts.get();
    }

    public synchronized void start() {
        if (!config.enabled()) {
            log.info("Watchdog disabled in config");
            return;
        }
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog");
            t.setDaemon(true);
            return t;
        });
        long seconds = config.check().toSeconds();
        executor.scheduleAtFixedRate(this::checkSafely, seconds, seconds, TimeUnit.SECONDS);
        log.info("Watchdog started: check every {} s, hang -- {} s without heartbeat",
                seconds, config.hangTimeout().toSeconds());
    }

    public boolean check() {
        return check(clock.instant());
    }

    public boolean check(Instant now) {
        Optional<HeartbeatSource.InFlight> current = source.inFlight();
        if (current.isEmpty()) {
            return false;
        }
        HeartbeatSource.InFlight f = current.get();
        Duration silent = Duration.between(f.lastBeat(), now);
        if (silent.compareTo(config.hangTimeout()) < 0) {
            return false;
        }
        Optional<HeartbeatSource.Busy> busy = f.busy().filter(b -> b.until().isAfter(now));
        if (busy.isPresent()) {
            log.atDebug().addKeyValue("event", "watchdog.busy").addKeyValue("reason", busy.get().reason())
                    .log("Heartbeat silent for {} s, but the thread is legitimately busy: {} (until {})",
                            silent.toSeconds(), busy.get().reason(), busy.get().until());
            return false;
        }

        String reason = "no activity for %d s while handling «%s»%s".formatted(
                silent.toSeconds(), shorten(f.what()),
                f.busy().map(b -> ", signal «" + b.reason() + "» expired").orElse(""));
        log.atError()
                .addKeyValue("event", "watchdog.hang")
                .addKeyValue("silent_seconds", silent.toSeconds())
                .addKeyValue("what", f.what())
                .addKeyValue("conversation", f.conversation().orElse(""))
                .log("Confirmed hang: {} -- restarting the worker thread", reason);
        Optional<String> conversation = Optional.empty();
        try {
            conversation = target.restart(reason);
        } catch (RuntimeException e) {
            log.error("Worker thread restart failed", e);
        }
        int n = restarts.incrementAndGet();
        log.atWarn().addKeyValue("event", "watchdog.restart").addKeyValue("count", n)
                .log("Worker thread restarted (total: {})", n);
        try {
            alerts.accept(conversation.or(f::conversation),
                    "🐶 The agent hung while handling «" + shorten(f.what()) + "» (" + reason
                            + ") and was restarted. Please repeat the request.");
        } catch (RuntimeException e) {
            log.warn("Cannot deliver restart notification: {}", e.toString());
        }
        return true;
    }

    private void checkSafely() {
        try {
            check();
        } catch (RuntimeException e) {
            log.error("Watchdog check failed", e);
        }
    }

    private static String shorten(String s) {
        String one = s == null ? "" : s.replace('\n', ' ').strip();
        return one.length() <= 60 ? one : one.substring(0, 59) + "…";
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
