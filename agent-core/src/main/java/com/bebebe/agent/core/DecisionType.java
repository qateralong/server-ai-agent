package com.bebebe.agent.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum DecisionType {

    REPLY("reply"),

    RUN_SCRIPT("run_script"),

    USE_SCRIPT("use_script"),

    FIX_LAST_SCRIPT("fix_last_script"),

    TOOL_CALL("tool_call"),

    UNKNOWN("");

    private final String wireName;

    DecisionType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean isSupported() {
        return this != UNKNOWN;
    }

    public String family() {
        return switch (this) {
            case REPLY -> "reply";
            case RUN_SCRIPT, USE_SCRIPT, FIX_LAST_SCRIPT -> "script";
            case TOOL_CALL -> "tool";
            case UNKNOWN -> "unknown";
        };
    }

    public static DecisionType fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(normalized))
                .findFirst()
                .orElse(UNKNOWN);
    }

    public static java.util.List<String> supportedWireNames() {
        return supportedWireNames(true);
    }

    public static java.util.List<String> supportedWireNames(boolean scripts) {
        return Arrays.stream(values())
                .filter(DecisionType::isSupported)
                .filter(t -> scripts || !t.isScript())
                .map(DecisionType::wireName)
                .toList();
    }

    public boolean isScript() {
        return this == RUN_SCRIPT || this == USE_SCRIPT || this == FIX_LAST_SCRIPT;
    }

    public static Optional<DecisionType> known(String value) {
        DecisionType type = fromWire(value);
        return type == UNKNOWN ? Optional.empty() : Optional.of(type);
    }
}
