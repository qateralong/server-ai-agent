package com.bebebe.agent.core;

public enum MessageSource {

    TELEGRAM("Telegram"),

    VOICE("Voice"),

    SYSTEM("System"),

    CLIPBOARD("Clipboard"),

    /** A picture the user sent: a screenshot of an error, a photo of something to look at. */
    IMAGE("Image");

    private final String title;

    MessageSource(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
