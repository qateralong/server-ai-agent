package com.bebebe.agent.memory;

import java.util.Arrays;
import java.util.Locale;

/**
 * Where a fact came from, which is not the same question as whether it is true.
 *
 * <p>Everything used to look identical: a line of text with a date. But "пользователь попросил это
 * запомнить" and "модель решила, что это стоит запомнить" deserve different amounts of trust, and
 * the difference was invisible both to the ranking and to the model reading the prompt. An
 * inference presented with the same confidence as a statement is how an agent ends up telling
 * somebody, with a straight face, something they never said.
 */
public enum FactSource {

    /** The model decided this was worth keeping while reading the conversation. */
    EXTRACTED("extracted"),

    /** The user asked for it directly: «запомни, что…». */
    STATED("stated"),

    /** A human looked at it and said it was right. The strongest thing memory can have. */
    CONFIRMED("confirmed");

    private final String wireName;

    FactSource(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** How the fact is qualified for the model; empty when there is nothing to warn about. */
    public String marker() {
        return switch (this) {
            case EXTRACTED -> "";
            case STATED -> " [со слов пользователя]";
            case CONFIRMED -> " [подтверждено]";
        };
    }

    public static FactSource fromWire(String value) {
        if (value == null) {
            return EXTRACTED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.wireName.equals(normalized))
                .findFirst()
                .orElse(EXTRACTED);
    }
}
