package com.bebebe.agent.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * A clock that ticks like its base clock but always answers with the <b>user's</b> time zone,
 * read at the moment of the question rather than fixed at construction.
 *
 * <p>This exists because the zone is a live setting and because nearly everything that has to
 * know "what time is it for the person" -- the prompt's "Now:" block, {@code get_current_time},
 * the reminder screens, and the next firing time of a repeating job -- already receives a
 * {@link Clock} and asks it for {@link Clock#getZone()}. Threading a separate zone through all
 * of them would have meant changing every signature; swapping the clock changes none.
 *
 * <p>Before this, the clock was {@link Clock#systemDefaultZone()} -- the zone of the machine
 * running the process. On the Stage 2 server that is the wrong machine.
 */
public final class UserClock extends Clock {

    private final Clock base;
    private final Supplier<ZoneId> zone;

    public UserClock(Clock base, Supplier<ZoneId> zone) {
        this.base = base;
        this.zone = zone;
    }

    /**
     * Ticks like {@code base}, but reports the zone the user chose. While no zone has been
     * chosen the base clock's own zone is kept -- in production that is the machine's zone,
     * which is the honest stand-in, and in tests it is whatever the test fixed.
     */
    public static UserClock following(Clock base, AppSettings settings) {
        return new UserClock(base, () -> settings.timezoneChosen() ? settings.zone() : base.getZone());
    }

    @Override
    public ZoneId getZone() {
        return zone.get();
    }

    /**
     * Asking for a fixed zone is a deliberate override (the {@code timezone} argument of
     * {@code get_current_time}), so it wins and the result no longer follows the setting.
     */
    @Override
    public Clock withZone(ZoneId other) {
        return base.withZone(other);
    }

    @Override
    public Instant instant() {
        return base.instant();
    }

    @Override
    public String toString() {
        return "UserClock[" + zone.get() + "]";
    }
}
