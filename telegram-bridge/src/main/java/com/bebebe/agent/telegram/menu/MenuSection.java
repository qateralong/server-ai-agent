package com.bebebe.agent.telegram.menu;

import java.util.Optional;

public enum MenuSection {

    POWER("power", "⚡ Power",
            "Switching the agent on and off."),

    SETTINGS("settings", "⚙️ Settings",
            "Editing config.toml right from the chat: model, provider, allow-list, toggles."),

    MEMORY("memory", "🧠 Memory",
            "What the agent remembers about you: facts, preferences, important decisions."),

    REMINDERS("reminders", "⏰ Reminders",
            "One-off and recurring reminders."),

    STATUS("status", "📊 Status",
            "Subsystem state: model, STT, TTS, scheduler, watchdog."),

    CONFIRMATIONS("confirm", "✅ Confirmations",
            "Actions the agent wants to perform and is waiting for your \"yes\"."),

    NOTES("notes", "📝 Notes and lists",
            "Notes and lists handled by the notes_tool tool."),

    PERSONAS("personas", "🎭 Personas",
            "Sets of system prompts and reply styles."),

    LOGS("logs", "📜 Logs",
            "Latest server events with a level filter; full logs are files on disk.");

    private final String id;
    private final String title;
    private final String description;

    MenuSection(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public boolean isImplemented() {
        return this == POWER;
    }

    public static Optional<MenuSection> byId(String id) {
        for (MenuSection section : values()) {
            if (section.id.equals(id)) {
                return Optional.of(section);
            }
        }
        return Optional.empty();
    }
}
