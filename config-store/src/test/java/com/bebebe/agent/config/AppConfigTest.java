package com.bebebe.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @TempDir
    Path tempDir;

    private static final String SAMPLE = """
            [telegram]
            enabled = true
            bot_token = "123:abc"
            allowed_usernames = ["@QaterAlong", " Second "]
            allowed_chat_ids = [111, 222]
            poll_timeout_seconds = 45

            [ollama]
            model = "gpt-oss:120b"
            temperature = 0.3
            """;

    @Test
    void readsSectionsAndTypes() {
        AppConfig config = AppConfig.fromToml(SAMPLE);
        ConfigSection telegram = config.section("telegram");

        assertTrue(telegram.bool("enabled", false));
        assertEquals("123:abc", telegram.requiredString("bot_token"));
        assertEquals(Set.of("@qateralong", "second"), telegram.lowercaseSet("allowed_usernames"));
        assertEquals(Set.of(111L, 222L), telegram.longSet("allowed_chat_ids"));
        assertEquals(Duration.ofSeconds(45), telegram.seconds("poll_timeout_seconds", Duration.ZERO));
        assertEquals(0.3, config.section("ollama").doubleValue("temperature").orElseThrow());
    }

    @Test
    void missingSectionIsEmptyNotError() {

        AppConfig config = AppConfig.fromToml(SAMPLE);

        assertFalse(config.hasSection("scheduler"));
        assertTrue(config.optionalSection("scheduler").isEmpty());
        assertEquals("дефолт", config.section("scheduler").string("что-угодно", "дефолт"));
        assertEquals(List.of(), config.section("scheduler").stringList("список"));
    }

    @Test
    void requiredParameterNamesFullKey() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> AppConfig.fromToml("[telegram]\n").section("telegram").requiredString("bot_token"));

        assertTrue(e.getMessage().contains("telegram.bot_token"), e.getMessage());
    }

    @Test
    void wrongTypeNamesFullKeyAndExpectation() {
        ConfigSection section = AppConfig.fromToml("""
                [telegram]
                enabled = "да"
                poll_timeout_seconds = "много"
                allowed_chat_ids = "не список"
                """).section("telegram");

        assertTrue(assertThrows(ConfigException.class, () -> section.bool("enabled"))
                .getMessage().contains("telegram.enabled"));
        assertTrue(assertThrows(ConfigException.class, () -> section.integer("poll_timeout_seconds"))
                .getMessage().contains("must be a number"));
        assertTrue(assertThrows(ConfigException.class, () -> section.longSet("allowed_chat_ids"))
                .getMessage().contains("must be a list"));
    }

    @Test
    void emptyStringCountsAsMissingValue() {
        ConfigSection section = AppConfig.fromToml("""
                [ollama]
                api_key = ""
                """).section("ollama");

        assertTrue(section.string("api_key").isEmpty());
        assertEquals("дефолт", section.string("api_key", "дефолт"));
    }

    @Test
    void readsFileAndClosesIt() throws IOException {
        Path file = tempDir.resolve("config.toml");
        Files.writeString(file, SAMPLE, StandardCharsets.UTF_8);

        AppConfig config = AppConfig.load(file);

        assertEquals("123:abc", config.section("telegram").requiredString("bot_token"));
        assertEquals(file, config.path());
    }

    @Test
    void failsWithHintIfFileMissing() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> AppConfig.load(tempDir.resolve("нет-такого.toml")));

        assertTrue(e.getMessage().contains("agent.example.toml"), e.getMessage());
    }

    @Test
    void doesNotPrintContentsInToString() {
        assertFalse(AppConfig.fromToml(SAMPLE).toString().contains("123:abc"));
    }

    @Test
    void systemPropertyTakesPrecedence() {
        String previous = System.getProperty(ConfigPaths.PROPERTY);
        try {
            System.setProperty(ConfigPaths.PROPERTY, "/tmp/явно-заданный.toml");
            assertEquals(Path.of("/tmp/явно-заданный.toml"), ConfigPaths.candidates().getFirst());
        } finally {
            if (previous == null) {
                System.clearProperty(ConfigPaths.PROPERTY);
            } else {
                System.setProperty(ConfigPaths.PROPERTY, previous);
            }
        }
    }
}
