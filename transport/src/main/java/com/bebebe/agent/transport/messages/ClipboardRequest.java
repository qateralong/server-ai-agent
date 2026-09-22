package com.bebebe.agent.transport.messages;

public record ClipboardRequest(int maxChars) {

    public static ClipboardRequest standard() {
        return new ClipboardRequest(20_000);
    }
}
