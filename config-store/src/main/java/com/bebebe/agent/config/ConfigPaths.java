package com.bebebe.agent.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigPaths {

    public static final String PROPERTY = "bebebe.config";

    public static final String ENV = "BEBEBE_CONFIG";

    private ConfigPaths() {
    }

    public static Path defaultPath() {
        return candidates().stream()
                .filter(Files::isReadable)
                .findFirst()
                .orElseGet(ConfigPaths::xdgPath);
    }

    public static List<Path> candidates() {
        List<Path> candidates = new ArrayList<>();

        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            candidates.add(Path.of(property));
        }
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        candidates.add(xdgPath());
        candidates.add(Path.of("config", "agent.toml"));

        return candidates;
    }

    public static Path xdgPath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg == null || xdg.isBlank())
                ? Path.of(System.getProperty("user.home"), ".config")
                : Path.of(xdg);
        return base.resolve("bebebe-agent").resolve("config.toml");
    }
}
