package com.bebebe.agent.memory;

import java.util.Arrays;
import java.util.Locale;

public enum FactCategory {

    EVENT("event", "event", true),

    PREFERENCE("preference", "preference", true),

    AGREEMENT("agreement", "agreement", true),

    TRAIT("trait", "trait", true),

    PROCEDURE("procedure", "procedure", true),

    /**
     * How an attempt by the agent itself ended.
     *
     * <p>Not extractable: the model does not choose this category while reading a conversation,
     * the core writes it when a request has run out of ways to succeed. Extraction is explicitly
     * told not to record one-off tasks, and rightly so -- but "открыть Spotify скриптом не
     * получилось, нет сессии D-Bus" is not a one-off, it is the thing the agent will need
     * tomorrow when it is asked again and starts down the same dead end.
     */
    OUTCOME("outcome", "outcome", false);

    private final String wireName;
    private final String title;
    private final boolean extractable;

    FactCategory(String wireName, String title) {
        this(wireName, title, true);
    }

    FactCategory(String wireName, String title, boolean extractable) {
        this.wireName = wireName;
        this.title = title;
        this.extractable = extractable;
    }

    /** Whether extraction may choose this category; the rest are written by the agent itself. */
    public boolean extractable() {
        return extractable;
    }

    /** The categories extraction is allowed to choose from. */
    public static java.util.List<FactCategory> extractableValues() {
        return Arrays.stream(values()).filter(FactCategory::extractable).toList();
    }

    public String wireName() {
        return wireName;
    }

    public String title() {
        return title;
    }

    public static FactCategory fromWire(String value) {
        if (value == null) {
            return EVENT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(c -> c.wireName.equals(normalized) || c.title.equals(normalized))
                .findFirst()
                .orElse(EVENT);
    }
}
