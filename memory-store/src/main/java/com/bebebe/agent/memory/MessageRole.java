package com.bebebe.agent.memory;

public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String wireName;

    MessageRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MessageRole fromWire(String value) {
        return "assistant".equalsIgnoreCase(value) ? ASSISTANT : USER;
    }
}
