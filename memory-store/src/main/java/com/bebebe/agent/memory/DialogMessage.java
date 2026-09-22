package com.bebebe.agent.memory;

import java.time.Instant;

public record DialogMessage(
        long id,
        long sessionId,
        MessageRole role,
        String text,
        Instant at,
        String source,
        String traceId
) {
}
