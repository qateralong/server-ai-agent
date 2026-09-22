package com.bebebe.agent.scheduler;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;
import java.time.Duration;

public record SchedulerConfig(Path dbPath, Duration tick, Duration lateTolerance, boolean desktopNotifications) {

    public static final String SECTION = "scheduler";

    public static final Duration DEFAULT_TICK = Duration.ofSeconds(30);

    public static final Duration DEFAULT_LATE_TOLERANCE = Duration.ofMinutes(5);

    public SchedulerConfig {
        if (tick == null || tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("scheduler.tick_seconds must be > 0");
        }
        if (lateTolerance == null || lateTolerance.isNegative()) {
            throw new IllegalArgumentException("scheduler.late_tolerance_minutes cannot be negative");
        }
    }

    public static SchedulerConfig from(ConfigSection section) {
        return new SchedulerConfig(
                expand(section.string("db_path", "~/.local/share/bebebe-agent/scheduler.db")),
                section.seconds("tick_seconds", DEFAULT_TICK),
                Duration.ofMinutes(section.longValue("late_tolerance_minutes").orElse(DEFAULT_LATE_TOLERANCE.toMinutes())),
                section.bool("desktop_notifications", true));
    }

    static Path expand(String raw) {
        if (raw == null || raw.isBlank()) {
            return Path.of("");
        }
        String value = raw.trim();
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }
}
