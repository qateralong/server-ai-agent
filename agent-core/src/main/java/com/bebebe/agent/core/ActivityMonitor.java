package com.bebebe.agent.core;

import com.bebebe.agent.watchdog.HeartbeatSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class ActivityMonitor implements HeartbeatSource {

    private final Clock clock;

    private volatile String what;
    private volatile String conversation;
    private volatile Instant startedAt;
    private volatile Instant lastBeat;
    private volatile Busy busy;

    public ActivityMonitor() {
        this(Clock.systemUTC());
    }

    public ActivityMonitor(Clock clock) {
        this.clock = clock;
    }

    void begin(String what, String conversation) {
        Instant now = clock.instant();
        this.what = what;
        this.conversation = conversation;
        this.startedAt = now;
        this.lastBeat = now;
        this.busy = null;
    }

    void beat() {
        lastBeat = clock.instant();
    }

    AutoCloseable busy(String reason, Duration expected) {
        Busy previous = busy;
        busy = new Busy(reason, clock.instant().plus(expected));
        return () -> {
            busy = previous;
            beat();
        };
    }

    void end() {
        what = null;
        conversation = null;
        startedAt = null;
        busy = null;
    }

    @Override
    public Optional<InFlight> inFlight() {
        String w = what;
        Instant started = startedAt;
        if (w == null || started == null) {
            return Optional.empty();
        }
        return Optional.of(new InFlight(w, Optional.ofNullable(conversation), started, lastBeat,
                Optional.ofNullable(busy)));
    }

    public Optional<Duration> silence() {
        Instant last = lastBeat;
        return last == null ? Optional.empty() : Optional.of(Duration.between(last, clock.instant()));
    }
}
