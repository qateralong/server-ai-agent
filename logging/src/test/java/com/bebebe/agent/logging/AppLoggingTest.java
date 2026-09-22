package com.bebebe.agent.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppLoggingTest {

    @Test
    void levelIsNormalisedCaseInsensitively() {
        assertEquals("DEBUG", AppLogging.normalize("debug", "INFO"));
        assertEquals("WARN", AppLogging.normalize(" warn ", "INFO"));
        assertEquals("INFO", AppLogging.normalize("", "INFO"));
        assertEquals("INFO", AppLogging.normalize(null, "INFO"));
    }

    @Test
    void unknownLevelGivesFallback() {
        assertEquals("INFO", AppLogging.normalize("VERBOSE", "INFO"));
        assertNull(AppLogging.normalize("VERBOSE", null));
    }

    @Test
    void subsystemNameBecomesPackage() {

        assertEquals("com.bebebe.agent.script", AppLogging.resolveLoggerName("script-runtime"));
        assertEquals("com.bebebe.agent.core", AppLogging.resolveLoggerName("agent-core"));
        assertEquals("com.bebebe.agent.telegram", AppLogging.resolveLoggerName("telegram"));
        assertEquals("com.bebebe.agent.ollama.OllamaClient",
                AppLogging.resolveLoggerName("com.bebebe.agent.ollama.OllamaClient"));
    }

    @Test
    void setLevelChangesLevelOnTheFly() {
        String before = AppLogging.currentLevel();
        try {
            AppLogging.setLevel("debug");
            assertEquals("DEBUG", AppLogging.currentLevel());
            AppLogging.setLevel("WARN");
            assertEquals("WARN", AppLogging.currentLevel());
        } finally {
            AppLogging.setLevel(before);
        }
    }

    @Test
    void setLevelRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> AppLogging.setLevel("loud"));
    }
}
