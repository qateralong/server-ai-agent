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
 *   <li>{@code source} -- who decided this was worth remembering, the user or the model.
 *   <li>{@code keywords} -- the other words somebody might use to ask about this, written down
 *       once when the fact is stored. Recall is lexical, so a fact found only by its own wording
 *       is invisible to a question phrased differently; expanding at write time costs one shot,
 *       expanding at read time would cost one on every message.
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
        Instant lastUsedAt,
        FactSource source,
        List<String> keywords
) {

    public Fact {
        text = text == null ? "" : text.strip();
        category = category == null ? FactCategory.EVENT : category;
        entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
        mentionCount = Math.max(1, mentionCount);
        usedCount = Math.max(0, usedCount);
        source = source == null ? FactSource.EXTRACTED : source;
        keywords = keywords == null ? List.of() : keywords.stream()
                .filter(k -> k != null && !k.isBlank()).map(String::strip).distinct().toList();
    }

    /** A brand new fact: nothing has happened to it yet. */
    public Fact(long id, String text, FactCategory category, LocalDate factDate,
                Long sourceMessageId, List<Long> entityIds, Instant createdAt) {
        this(id, text, category, factDate, sourceMessageId, entityIds, createdAt, null, null, 1, 0, null,
                FactSource.EXTRACTED, List.of());
    }

    /**
     * The text plus everything else the fact can be found by.
     *
     * <p>Used for matching a question against the fact, and <b>not</b> for deciding whether two
     * facts are the same thing: near-duplicate detection compares what was actually said, or two
     * different facts sharing a generous set of keywords would start swallowing each other.
     */
    public String searchText() {
        return keywords.isEmpty() ? text : text + " " + String.join(" ", keywords);
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
        sb.append(source.marker());
        return sb.toString();
    }

    /** The same thing for a human: no bullet, no number, no service marks. */
    public String display() {
        return factDate == null ? text : factDate + ": " + text;
    }
}
