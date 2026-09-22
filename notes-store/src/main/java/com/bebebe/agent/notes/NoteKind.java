package com.bebebe.agent.notes;

public enum NoteKind {
    LIST("list", "lists", "📋 List"),
    NOTE("note", "notes", "📝 Note");

    private final String wire;
    private final String folder;
    private final String title;

    NoteKind(String wire, String folder, String title) {
        this.wire = wire;
        this.folder = folder;
        this.title = title;
    }

    public String wire() {
        return wire;
    }

    public String folder() {
        return folder;
    }

    public String title() {
        return title;
    }

    public static NoteKind fromWire(String raw) {
        return raw != null && raw.strip().equalsIgnoreCase("note") ? NOTE : LIST;
    }
}
