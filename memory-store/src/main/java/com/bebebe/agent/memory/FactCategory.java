package com.bebebe.agent.memory;

import java.util.Arrays;
import java.util.Locale;

public enum FactCategory {

    EVENT("event", "event"),

    PREFERENCE("preference", "preference"),

    AGREEMENT("agreement", "agreement"),

    TRAIT("trait", "trait"),

    PROCEDURE("procedure", "procedure");

    private final String wireName;
    private final String title;

    FactCategory(String wireName, String title) {
        this.wireName = wireName;
        this.title = title;
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
