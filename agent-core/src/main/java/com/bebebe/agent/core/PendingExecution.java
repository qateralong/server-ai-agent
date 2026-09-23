package com.bebebe.agent.core;

import com.bebebe.agent.script.library.ScriptEntry;

import java.time.Duration;
import java.time.Instant;

public record PendingExecution(
        String token,
        UserMessage message,
        ScriptEntry script,
        String code,
        RequestBudget budget,
        TaskAttempts attempts,
        Instant expiresAt
) {

    public static final Duration TTL = Duration.ofMinutes(10);

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
