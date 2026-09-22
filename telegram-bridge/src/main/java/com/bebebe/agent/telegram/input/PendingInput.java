package com.bebebe.agent.telegram.input;

import java.time.Duration;
import java.time.Instant;

public record PendingInput(
        String fieldKey,
        String prompt,
        int menuMessageId,
        Instant expiresAt,
        InputHandler handler
) {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    public PendingInput {
        if (fieldKey == null || fieldKey.isBlank()) {
            throw new IllegalArgumentException("PendingInput: fieldKey not set");
        }
        if (handler == null) {
            throw new IllegalArgumentException("PendingInput: handler not set");
        }
    }

    public static PendingInput of(String fieldKey, String prompt, int menuMessageId, InputHandler handler) {
        return new PendingInput(fieldKey, prompt, menuMessageId, Instant.now().plus(DEFAULT_TTL), handler);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
