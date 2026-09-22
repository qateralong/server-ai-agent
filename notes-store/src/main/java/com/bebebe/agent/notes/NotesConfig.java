package com.bebebe.agent.notes;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;

public record NotesConfig(Path dir, boolean gitHistory) {

    public static final String SECTION = "notes";
    public static final String DEFAULT_DIR = "~/.agent-data/notes";

    public static NotesConfig from(ConfigSection section) {
        return new NotesConfig(
                expand(section.string("dir", DEFAULT_DIR)),
                section.bool("git_history", true));
    }

    static Path expand(String raw) {
        String s = raw.strip();
        if (s.equals("~") || s.startsWith("~/")) {
            s = System.getProperty("user.home") + s.substring(1);
        }
        return Path.of(s).toAbsolutePath().normalize();
    }
}
