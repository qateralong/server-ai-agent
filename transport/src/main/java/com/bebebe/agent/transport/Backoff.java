package com.bebebe.agent.transport;

import java.time.Duration;
import java.util.random.RandomGenerator;

public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private final RandomGenerator random;
    private Duration next;

    public Backoff(Duration initial, Duration max) {
        this(initial, max, RandomGenerator.getDefault());
    }

    Backoff(Duration initial, Duration max, RandomGenerator random) {
        if (initial.isZero() || initial.isNegative() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("Pause must be > 0 and not above the ceiling");
        }
        this.initial = initial;
        this.max = max;
        this.random = random;
        this.next = initial;
    }

    public synchronized Duration next() {
        Duration base = next;
        Duration doubled = next.multipliedBy(2);
        next = doubled.compareTo(max) > 0 ? max : doubled;
        double jitter = 0.8 + random.nextDouble() * 0.4;
        return Duration.ofMillis(Math.max(1, Math.round(base.toMillis() * jitter)));
    }

    public synchronized void reset() {
        next = initial;
    }

    public synchronized Duration peek() {
        return next;
    }
}
