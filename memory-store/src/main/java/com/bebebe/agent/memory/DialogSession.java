package com.bebebe.agent.memory;

import java.time.Instant;

/**
 * A continuous conversation in one channel.
 *
 * <p>{@code summary} is what is left of it once it is over. While the session is alive its whole
 * log goes into the prompt; when it closes the log stops being context and the session would
 * vanish from the agent's world entirely -- "что мы вчера обсуждали?" had no answer at all. The
 * summary is the cheap part that stays: a few lines about what the conversation was and what was
 * left open.
 */
public record DialogSession(
        long id,
        String conversationKey,
        Instant startedAt,
        Instant lastMessageAt,
        Instant endedAt,
        long consolidatedUpTo,
        String summary
) {

    public DialogSession {
        summary = summary == null ? "" : summary.strip();
    }

    public DialogSession(long id, String conversationKey, Instant startedAt, Instant lastMessageAt,
                         Instant endedAt, long consolidatedUpTo) {
        this(id, conversationKey, startedAt, lastMessageAt, endedAt, consolidatedUpTo, "");
    }

    public boolean isActive() {
        return endedAt == null;
    }

    public boolean hasSummary() {
        return !summary.isEmpty();
    }
}
