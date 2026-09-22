package com.bebebe.agent.core;

public enum MessageSource {

    TELEGRAM("Telegram"),

    VOICE("Voice"),

    SYSTEM("System"),

    CLIPBOARD("Clipboard");

    private final String title;

    MessageSource(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
