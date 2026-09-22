package com.bebebe.agent.script.library;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;

public record LibraryConfig(Path dbPath, Path scriptsDir, int searchLimit) {

    public static final String SECTION = "library";

    public static final int DEFAULT_SEARCH_LIMIT = 3;

    public LibraryConfig {
        if (searchLimit < 1) {
            throw new IllegalArgumentException("library.search_limit must be >= 1");
        }
    }

    public static LibraryConfig from(ConfigSection section) {
        return new LibraryConfig(
                expand(section.string("db_path", "~/.local/share/bebebe-agent/library.db")),
                expand(section.string("scripts_dir", "~/.local/share/bebebe-agent/library")),
                section.integer("search_limit", DEFAULT_SEARCH_LIMIT));
    }

    static Path expand(String raw) {
        if (raw == null || raw.isBlank()) {
            return Path.of("");
        }
        String value = raw.trim();
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }
}
