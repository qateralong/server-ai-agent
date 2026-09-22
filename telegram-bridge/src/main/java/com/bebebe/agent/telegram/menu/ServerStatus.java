package com.bebebe.agent.telegram.menu;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record ServerStatus(
        boolean agentOn,
        String providerLine,
        boolean modelAvailable,
        String modelState,
        long sessionCalls,
        long sessionTokens,
        long sessionFailures,
        long totalCalls,
        List<String> clients,
        int watchdogRestarts,
        String version,
        String updates,
        Optional<String> lastError,
        Instant startedAt,
        List<String> diskLines,
        String configPath
) {

    public Duration uptime() {
        return Duration.between(startedAt, Instant.now());
    }
}
