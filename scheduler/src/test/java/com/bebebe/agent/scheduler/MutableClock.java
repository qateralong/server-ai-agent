package com.bebebe.agent.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

final class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new MutableClock(now, other);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
