package com.bebebe.agent.memory;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;
import java.time.Duration;

public record MemoryConfig(Path dbPath, Duration sessionIdle, int consolidateEvery, Duration consolidateInterval) {

    public static final String SECTION = "memory";

    public static final Duration DEFAULT_SESSION_IDLE = Duration.ofMinutes(60);

    public static final int DEFAULT_CONSOLIDATE_EVERY = 6;

    /**
     * How often an active session is consolidated even when the message counter has not been
     * reached. Without it a conversation that stops mid-way -- a hung worker the watchdog had to
     * restart, a laptop that lost power -- keeps its whole tail unextracted.
     */
    public static final Duration DEFAULT_CONSOLIDATE_INTERVAL = Duration.ofMinutes(10);

    public MemoryConfig {
        if (sessionIdle == null || sessionIdle.isZero() || sessionIdle.isNegative()) {
            throw new IllegalArgumentException("memory.session_idle_minutes must be > 0");
        }
        if (consolidateEvery < 1) {
            throw new IllegalArgumentException("memory.consolidate_every must be >= 1");
        }
        if (consolidateInterval == null || consolidateInterval.isNegative()) {
            throw new IllegalArgumentException("memory.consolidate_minutes must be >= 0");
        }
    }

    public static MemoryConfig from(ConfigSection section) {
        return new MemoryConfig(
                expand(section.string("db_path", "~/.local/share/bebebe-agent/memory.db")),
                Duration.ofMinutes(section.longValue("session_idle_minutes")
                        .orElse(DEFAULT_SESSION_IDLE.toMinutes())),
                section.integer("consolidate_every", DEFAULT_CONSOLIDATE_EVERY),
                Duration.ofMinutes(section.longValue("consolidate_minutes")
                        .orElse(DEFAULT_CONSOLIDATE_INTERVAL.toMinutes())));
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
