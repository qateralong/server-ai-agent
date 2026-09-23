package com.bebebe.agent.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One remembered statement.
 *
 * <p>Beyond the text itself a fact carries what happened to it afterwards, and that is what
 * turns a growing pile into a memory:
 *
 * <ul>
 *   <li>{@code supersededAt} -- the fact is no longer current. It was either replaced by a newer
 *       one ({@code supersededBy}) or retracted by the user. Nothing is deleted: the history of
 *       what was believed and when stays readable.
 *   <li>{@code mentionCount} -- how many times extraction ran into the same thing again. A fact
 *       confirmed three times is worth more than one heard once, and until this counter existed
 *       a repeat was simply dropped as a duplicate and told nobody anything.
 *   <li>{@code usedCount} / {@code lastUsedAt} -- how often the model actually leaned on it. The
 *       only honest signal for ranking; everything else is a guess about what might be relevant.
 * </ul>
 */
public record Fact(
        long id,
        String text,
        FactCategory category,
        LocalDate factDate,
        Long sourceMessageId,
        List<Long> entityIds,
        Instant createdAt,
        Long supersededBy,
        Instant supersededAt,
        int mentionCount,
        int usedCount,
        Instant lastUsedAt
) {

    public Fact {
        text = text == null ? "" : text.strip();
        category = category == null ? FactCategory.EVENT : category;
        entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
        mentionCount = Math.max(1, mentionCount);
        usedCount = Math.max(0, usedCount);
    }

    /** A brand new fact: nothing has happened to it yet. */
    public Fact(long id, String text, FactCategory category, LocalDate factDate,
                Long sourceMessageId, List<Long> entityIds, Instant createdAt) {
        this(id, text, category, factDate, sourceMessageId, entityIds, createdAt, null, null, 1, 0, null);
    }

    /** Still believed: neither replaced nor retracted. */
    public boolean isCurrent() {
        return supersededAt == null;
    }

    /**
     * How the fact is shown to the model. The number is not decoration: the model quotes it back
     * in {@code used_facts} and passes it to {@code forget}, so recall gets a real usage signal
     * and a wrong fact can be named precisely instead of described.
     */
    public String describeForModel() {
        StringBuilder sb = new StringBuilder("• #").append(id).append(' ');
        if (factDate != null) {
            sb.append(factDate).append(": ");
        }
        sb.append(text);
        if (category == FactCategory.PROCEDURE) {
            sb.append(" [procedure]");
        }
        if (mentionCount > 1) {
            sb.append(" [confirmed ×").append(mentionCount).append(']');
        }
        return sb.toString();
    }

    /** The same thing for a human: no bullet, no number, no service marks. */
    public String display() {
        return factDate == null ? text : factDate + ": " + text;
    }
}
