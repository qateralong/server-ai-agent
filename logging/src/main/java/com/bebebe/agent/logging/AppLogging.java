package com.bebebe.agent.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AppLogging {

    public static final String PROPERTY_DIR = "bebebe.log.dir";
    public static final String PROPERTY_LEVEL = "bebebe.log.level";

    public static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private AppLogging() {
    }

    public static void bootstrap() {
        Logger log = LoggerFactory.getLogger(AppLogging.class);
        log.info("Logging bootstrapped with defaults (directory logs, level INFO); "
                + "will be reconfigured after the config is read");
    }

    public static void applyConfig(Path dir, String level, Map<String, String> overrides) {
        String normalizedLevel = normalize(level, "INFO");
        System.setProperty(PROPERTY_DIR, dir.toString());
        System.setProperty(PROPERTY_LEVEL, normalizedLevel);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        URL config = AppLogging.class.getClassLoader().getResource("logback.xml");
        if (config == null) {
            LoggerFactory.getLogger(AppLogging.class).error("logback.xml not found in classpath");
            return;
        }

        try {

            context.reset();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);
        } catch (JoranException e) {

            ch.qos.logback.classic.BasicConfigurator basic = new ch.qos.logback.classic.BasicConfigurator();
            basic.setContext(context);
            basic.configure(context);
            LoggerFactory.getLogger(AppLogging.class)
                    .error("Failed to apply logback.xml, running with the basic configuration", e);
            return;
        }

        overrides.forEach((loggerName, overrideLevel) -> {
            String normalized = normalize(overrideLevel, null);
            if (normalized == null) {
                LoggerFactory.getLogger(AppLogging.class)
                        .warn("Unknown level '{}' for {}, skipping", overrideLevel, loggerName);
                return;
            }
            context.getLogger(resolveLoggerName(loggerName)).setLevel(Level.toLevel(normalized));
        });

        Logger log = LoggerFactory.getLogger(AppLogging.class);
        log.info("Logging reconfigured: directory {}, level {}, overrides {}",
                dir.toAbsolutePath(), normalizedLevel, overrides.size());
        if (!overrides.isEmpty()) {
            log.info("Level overrides: {}", overrides);
        }
    }

    public static void setLevel(String level) {
        String normalized = normalize(level, null);
        if (normalized == null) {
            throw new IllegalArgumentException("Unknown logging level: " + level);
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger("com.bebebe.agent").setLevel(Level.toLevel(normalized));
        System.setProperty(PROPERTY_LEVEL, normalized);
        LoggerFactory.getLogger(AppLogging.class).info("Logging level: {}", normalized);
    }

    public static String currentLevel() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Level level = context.getLogger("com.bebebe.agent").getLevel();
        return level == null ? "INFO" : level.toString();
    }

    static String resolveLoggerName(String key) {
        String trimmed = key.trim();
        if (trimmed.contains(".")) {
            return trimmed;
        }
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "agent-core", "core" -> "com.bebebe.agent.core";
            case "script-runtime", "scripts", "script-library" -> "com.bebebe.agent.script";
            case "telegram-bridge", "telegram" -> "com.bebebe.agent.telegram";
            case "stt-bridge", "stt" -> "com.bebebe.agent.stt";
            case "tts-bridge", "tts" -> "com.bebebe.agent.tts";
            case "ollama-client", "ollama" -> "com.bebebe.agent.ollama";
            case "scheduler" -> "com.bebebe.agent.scheduler";
            case "watchdog" -> "com.bebebe.agent.watchdog";
            case "transport" -> "com.bebebe.agent.transport";
            case "config-store", "config" -> "com.bebebe.agent.config";
            case "ui" -> "com.bebebe.agent.ui";
            default -> "com.bebebe.agent." + trimmed;
        };
    }

    static String normalize(String level, String fallback) {
        if (level == null || level.isBlank()) {
            return fallback;
        }
        String upper = level.trim().toUpperCase(Locale.ROOT);
        return LEVELS.contains(upper) ? upper : fallback;
    }
}
