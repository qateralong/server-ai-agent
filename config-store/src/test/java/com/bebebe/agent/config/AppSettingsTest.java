package com.bebebe.agent.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {

    private static final String ORIGINAL = """
            # Конфиг AI-агентного слоя.
            # ВАЖНО: секреты лежат здесь открытым текстом.

            [ollama]
            # Ключ создаётся на https://ollama.com/settings/keys
            api_key = "старый-ключ"
            model = "gpt-oss:120b"
            timeout_seconds = 120

            [agent]
            enabled_on_start = false

            [telegram]
            enabled = true
            # Кому бот отвечает
            allowed_usernames = ["QaterAlong"]
            allowed_chat_ids = [42]
            """;

    @TempDir
    Path tempDir;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = tempDir.resolve("agent.toml");
        Files.writeString(file, ORIGINAL, StandardCharsets.UTF_8);
    }

    private AppSettings load() {
        return AppSettings.from(AppConfig.load(file));
    }

    @Test
    void readsInitialValues() {
        AppSettings settings = load();

        assertEquals("старый-ключ", settings.apiKey());
        assertEquals("gpt-oss:120b", settings.model());
        assertEquals(List.of("QaterAlong"), settings.allowedUsernames());
        assertFalse(settings.proactiveHints());
    }

    @Test
    void savingKeepsCommentsAndForeignKeys() throws IOException {

        AppSettings settings = load();
        settings.setModel("glm-5.3");
        settings.save();

        String saved = Files.readString(file);

        assertTrue(saved.contains("# Конфиг AI-агентного слоя."), saved);
        assertTrue(saved.contains("# Ключ создаётся на https://ollama.com/settings/keys"), saved);
        assertTrue(saved.contains("# Кому бот отвечает"), saved);
        assertTrue(saved.contains("timeout_seconds = 120"), saved);
        assertTrue(saved.contains("allowed_chat_ids = [42]"), saved);
        assertTrue(saved.contains("enabled_on_start = false"), saved);
        assertTrue(saved.contains("model = \"glm-5.3\""), saved);
    }

    @Test
    void savingSurvivesReread() {
        AppSettings settings = load();
        settings.setModel("kimi-k2.6");
        settings.setAllowedUsernames(List.of("@First", "SECOND"));
        settings.setProactiveHints(true);
        settings.setTelegramBotToken("111:AAA");
        settings.save();

        AppSettings reloaded = load();

        assertEquals("kimi-k2.6", reloaded.model());
        assertEquals(List.of("first", "second"), reloaded.allowedUsernames());
        assertTrue(reloaded.proactiveHints());
        assertEquals("111:AAA", reloaded.telegramBotToken());
    }

    @Test
    void newKeyIsAppendedToItsSection() throws IOException {

        AppSettings settings = load();
        settings.setProactiveHints(true);
        settings.save();

        String saved = Files.readString(file);
        int agentSection = saved.indexOf("[agent]");
        int telegramSection = saved.indexOf("[telegram]");
        int hints = saved.indexOf("proactive_hints");

        assertTrue(hints > agentSection, "proactive_hints must come after [agent]");
        assertTrue(hints < telegramSection, "proactive_hints must not end up in [telegram]");
    }

    @Test
    void normalisesAndDeduplicatesNames() {
        AppSettings settings = load();

        settings.setAllowedUsernames(List.of("@QaterAlong", "qateralong", " Second ", ""));

        assertEquals(List.of("qateralong", "second"), settings.allowedUsernames());
    }

    @Test
    void notifiesSubscribersOnlyOnRealChange() {
        AppSettings settings = load();
        List<SettingsField> seen = new ArrayList<>();
        settings.addListener(seen::add);

        settings.setModel("gpt-oss:120b");
        settings.setModel("glm-5.3");
        settings.setProactiveHints(false);
        settings.setProactiveHints(true);

        assertEquals(List.of(SettingsField.LLM_MODEL, SettingsField.PROACTIVE_HINTS), seen);
    }

    @Test
    void failingSubscriberDoesNotCancelChange() {
        AppSettings settings = load();
        settings.addListener(field -> {
            throw new IllegalStateException("subscriber failed");
        });
        List<SettingsField> seen = new ArrayList<>();
        settings.addListener(seen::add);

        settings.setModel("glm-5.3");

        assertEquals("glm-5.3", settings.model());
        assertEquals(List.of(SettingsField.LLM_MODEL), seen);
    }

    @Test
    void addAndRemoveName() {
        AppSettings settings = load();

        settings.addAllowedUsername("@Second");
        assertEquals(List.of("qateralong", "second"), settings.allowedUsernames());

        settings.removeAllowedUsername("QaterAlong");
        assertEquals(List.of("second"), settings.allowedUsernames());
    }

    @Test
    void doesNotPrintSecretsInLogOrToString() {
        AppSettings settings = load();
        settings.setTelegramBotToken("111:SECRET-TOKEN");

        assertFalse(settings.toString().contains("SECRET-TOKEN"));
        assertFalse(settings.toString().contains("старый-ключ"));
        assertEquals("<set>", settings.describe(SettingsField.TELEGRAM_BOT_TOKEN));

        settings.setTelegramBotToken("");
        assertEquals("<empty>", settings.describe(SettingsField.TELEGRAM_BOT_TOKEN));
    }

    @Test
    void savingWithoutFileGivesClearError() {
        AppSettings inMemory = AppSettings.from(AppConfig.fromToml("[ollama]\nmodel = \"m\"\n"));

        ConfigException e = assertThrows(ConfigException.class, inMemory::save);

        assertTrue(e.getMessage().contains("config not found"), e.getMessage());
    }

    @Test
    void writingKeepsFilePermissions() throws IOException {

        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            return;
        }

        AppSettings settings = load();
        settings.setModel("glm-5.3");
        settings.save();

        assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void fieldsKnowCostOfTheirChange() {
        assertEquals(SettingsField.Apply.RECONNECT, SettingsField.TELEGRAM_BOT_TOKEN.apply());
        assertFalse(SettingsField.TELEGRAM_BOT_TOKEN.warning().isEmpty());
        assertTrue(SettingsField.TELEGRAM_BOT_TOKEN.warning().contains("session"));

        assertEquals(SettingsField.Apply.LIVE, SettingsField.LLM_MODEL.apply());
        assertTrue(SettingsField.LLM_MODEL.warning().isEmpty());

        assertTrue(SettingsField.LLM_API_KEY.isSecret());
        assertTrue(SettingsField.TELEGRAM_BOT_TOKEN.isSecret());
        assertFalse(SettingsField.LLM_MODEL.isSecret());
        assertFalse(SettingsField.PROACTIVE_HINTS.isSecret());
    }

    @Test
    void oldConfigWithoutLlmSectionIsReadAsOllama() {
        AppSettings settings = AppSettings.from(AppConfig.fromToml("""
                [ollama]
                base_url = "http://localhost:11434"
                api_key = "k"
                model = "llama3.2"
                """));

        assertEquals(AppSettings.PROVIDER_OLLAMA, settings.provider());
        assertEquals("k", settings.apiKey());
        assertEquals("llama3.2", settings.model());
        assertEquals("http://localhost:11434", settings.endpoint(), "a non-default base_url becomes endpoint");
        assertEquals("", settings.slot(AppSettings.PROVIDER_CLAUDE).apiKey());
    }

    @Test
    void eachProviderHasItsSlotAndSwitchingKeepsThem() {
        AppSettings settings = AppSettings.from(AppConfig.fromToml("""
                [llm]
                provider = "claude"
                [llm.ollama]
                api_key = "ok"
                model = "gpt-oss:120b"
                [llm.claude]
                api_key = "ck"
                model = "claude-opus-5"
                endpoint = "https://proxy.local"
                """));
        java.util.List<SettingsField> seen = new java.util.ArrayList<>();
        settings.addListener(seen::add);

        assertEquals("claude", settings.provider());
        assertEquals("ck", settings.apiKey());
        assertEquals("https://proxy.local", settings.endpoint());

        settings.setProvider("ollama");
        assertEquals("ok", settings.apiKey());
        assertEquals("gpt-oss:120b", settings.model());
        assertEquals("", settings.endpoint(), "empty -- default address");
        settings.setModel("glm-5.3");
        settings.setProvider("claude");
        assertEquals("claude-opus-5", settings.model(), "Claude slot untouched");
        assertEquals("glm-5.3", settings.slot("ollama").model());
        assertEquals(java.util.List.of(SettingsField.LLM_PROVIDER, SettingsField.LLM_MODEL, SettingsField.LLM_PROVIDER), seen);
        assertEquals("ollama", AppSettings.normalizeProvider("unknown"), "unknown provider -- Ollama");
    }
}
