package com.bebebe.agent.memory;

import java.time.Instant;

public record DialogSession(
        long id,
        String conversationKey,
        Instant startedAt,
        Instant lastMessageAt,
        Instant endedAt,
        long consolidatedUpTo
) {

    public boolean isActive() {
        return endedAt == null;
    }
}
