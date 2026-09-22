package com.bebebe.agent.ollama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaConfigTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String content) throws IOException {
        Path file = tempDir.resolve("config.toml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readsFullSection() throws IOException {
        Path file = writeConfig("""
                [ollama]
                base_url = "https://ollama.com"
                api_key = "test-key"
                model = "gpt-oss:120b"
                timeout_seconds = 45
                temperature = 0.3
                num_ctx = 4096
                """);

        OllamaConfig config = OllamaConfig.load(file);

        assertEquals("https://ollama.com", config.baseUrl());
        assertEquals("test-key", config.apiKey());
        assertEquals("gpt-oss:120b", config.model());
        assertEquals(45, config.timeout().toSeconds());
        assertEquals(0.3, config.temperature());
        assertEquals(4096, config.numCtx());
    }

    @Test
    void substitutesDefaultsForOptionalFields() throws IOException {
        Path file = writeConfig("""
                [ollama]
                api_key = "k"
                """);

        OllamaConfig config = OllamaConfig.load(file);

        assertEquals(OllamaConfig.DEFAULT_BASE_URL, config.baseUrl());
        assertEquals(OllamaConfig.DEFAULT_MODEL, config.model());
        assertEquals(OllamaConfig.DEFAULT_TIMEOUT, config.timeout());
        assertNull(config.temperature());
        assertNull(config.numCtx());
    }

    @Test
    void stripsTrailingSlashInBaseUrl() throws IOException {
        Path file = writeConfig("""
                [ollama]
                base_url = "https://ollama.com/"
                """);

        assertEquals("https://ollama.com", OllamaConfig.load(file).baseUrl());
    }

    @Test
    void keyIsNeededOnlyForCloud() {
        OllamaConfig cloud = new OllamaConfig(
                "https://ollama.com", "k", "m", OllamaConfig.DEFAULT_TIMEOUT, null, null);
        assertTrue(cloud.requiresApiKey());
        assertTrue(cloud.bearerToken().isPresent());

        OllamaConfig local = OllamaConfig.localDefault();
        assertFalse(local.requiresApiKey());
        assertTrue(local.bearerToken().isEmpty());
    }

    @Test
    void doesNotPrintKeyInToString() {
        OllamaConfig config = new OllamaConfig(
                "https://ollama.com", "sk-do-not-log-this-value",
                "m", OllamaConfig.DEFAULT_TIMEOUT, null, null);

        assertFalse(config.toString().contains("sk-do-not-log-this-value"));
        assertTrue(config.toString().contains("<set>"));
    }

    @Test
    void rejectsNonAsciiKey() {

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new OllamaConfig("https://ollama.com", "ключ-кириллицей",
                        "m", OllamaConfig.DEFAULT_TIMEOUT, null, null));
        assertTrue(e.getMessage().contains("api_key"), e.getMessage());
    }

    @Test
    void failsIfFileMissing() {
        OllamaException e = assertThrows(OllamaException.class,
                () -> OllamaConfig.load(tempDir.resolve("нет-такого.toml")));
        assertTrue(e.getMessage().contains("Config not found"));
    }

    @Test
    void failsWithoutOllamaSection() throws IOException {
        Path file = writeConfig("""
                [agent]
                persona = "default"
                """);

        OllamaException e = assertThrows(OllamaException.class, () -> OllamaConfig.load(file));
        assertTrue(e.getMessage().contains("[ollama]"));
    }

    @Test
    void findsConfigBySystemProperty() throws IOException {
        Path file = writeConfig("""
                [ollama]
                api_key = "k"
                """);
        String previous = System.getProperty("bebebe.config");
        try {
            System.setProperty("bebebe.config", file.toString());
            assertEquals(file, OllamaConfig.defaultPath());
        } finally {
            if (previous == null) {
                System.clearProperty("bebebe.config");
            } else {
                System.setProperty("bebebe.config", previous);
            }
        }
    }
}
