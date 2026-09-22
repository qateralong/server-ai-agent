package com.bebebe.agent.transport.messages;

public record ClipboardResult(boolean available, String text, boolean truncated, String error) {

    public static ClipboardResult of(String text, boolean truncated) {
        return new ClipboardResult(true, text, truncated, "");
    }

    public static ClipboardResult unavailable(String error) {
        return new ClipboardResult(false, "", false, error);
    }
}
