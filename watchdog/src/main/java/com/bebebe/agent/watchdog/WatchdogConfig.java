package com.bebebe.agent.watchdog;

import com.bebebe.agent.config.ConfigSection;

import java.time.Duration;

public record WatchdogConfig(boolean enabled, Duration check, Duration hangTimeout) {

    public static final String SECTION = "watchdog";

    public static WatchdogConfig from(ConfigSection section) {
        WatchdogConfig cfg = new WatchdogConfig(
                section.bool("enabled", true),
                section.seconds("check_seconds", Duration.ofSeconds(10)),
                section.seconds("hang_timeout_seconds", Duration.ofSeconds(90)));
        if (cfg.check.isZero() || cfg.check.isNegative()) {
            throw new IllegalArgumentException("watchdog.check_seconds must be > 0");
        }
        if (cfg.hangTimeout.compareTo(cfg.check) < 0) {
            throw new IllegalArgumentException("watchdog.hang_timeout_seconds cannot be less than check_seconds");
        }
        return cfg;
    }
}
