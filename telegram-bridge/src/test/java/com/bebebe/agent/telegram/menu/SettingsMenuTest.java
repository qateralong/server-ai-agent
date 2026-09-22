package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.telegram.input.InputOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsMenuTest {

    private static final List<String> MODELS = List.of("gpt-oss:120b", "glm-5.3", "kimi-k2.6");

    @TempDir
    Path tempDir;

    private AppSettings settings;
    private MenuController controller;
    private com.bebebe.agent.script.library.ScriptLibrary library;
    private com.bebebe.agent.memory.MemoryStore memory;

    @org.junit.jupiter.api.AfterEach
    void closeLibrary() {
        if (library != null) {
            library.close();
        }
        if (memory != null) {
            memory.close();
        }
    }

    private final AtomicReference<List<String>> catalog = new AtomicReference<>(MODELS);

    @BeforeEach
    void setUp() throws IOException {
        Path file = tempDir.resolve("agent.toml");
        Files.writeString(file, """
                [ollama]
                model = "gpt-oss:120b"

                [telegram]
                allowed_usernames = ["qateralong"]

                [agent]
                proactive_hints = false
                """, StandardCharsets.UTF_8);

        settings = AppSettings.from(AppConfig.load(file));
        library = com.bebebe.agent.telegram.TestLibrary.inDirectory(tempDir);
        memory = com.bebebe.agent.telegram.TestLibrary.memoryIn(tempDir);
        controller = new MenuController(new AgentSwitch(false), settings, catalog::get,
                library, com.bebebe.agent.telegram.TestLibrary.NO_CONFIRM,
                memory, com.bebebe.agent.telegram.TestLibrary.NO_MEMORY_ACTIONS);
    }

    private static String keyboard(MenuScreen screen) {
        return screen.keyboard().inlineKeyboard().toString();
    }

    @Test
    void chatShowsNeitherTokenNorKey() {

        settings.setTelegramBotToken("111:SUPER-SECRET");
        settings.setApiKey("sk-SUPER-SECRET");

        MenuScreen screen = controller.screenFor(MenuSection.SETTINGS);

        assertFalse(screen.text().contains("SUPER-SECRET"), screen.text());
        assertTrue(screen.text().contains("set"));
        assertFalse(keyboard(screen).contains("token"));
        assertFalse(keyboard(screen).contains("api_key"));
    }

    @Test
    void rootScreenShowsCurrentValues() {
        MenuScreen screen = controller.screenFor(MenuSection.SETTINGS);

        assertTrue(screen.text().contains("gpt-oss:120b"));
        assertTrue(screen.text().contains("Hints: off"));
        assertEquals(MenuSection.SETTINGS, screen.section());
    }

    @Test
    void modelListMarksCurrent() {
        MenuScreen screen = controller.handle(CallbackData.modelList()).screen();

        assertTrue(keyboard(screen).contains("✅ gpt-oss:120b"), keyboard(screen));
        assertTrue(keyboard(screen).contains("glm-5.3"));
    }

    @Test
    void modelButtonCarriesIndexNotName() {

        MenuScreen screen = controller.handle(CallbackData.modelList()).screen();

        assertTrue(keyboard(screen).contains("set:model:pick:1"), keyboard(screen));
        assertFalse(keyboard(screen).contains("set:model:pick:glm-5.3"));
    }

    @Test
    void modelChoiceAppliesAndPersists() {
        controller.handle(CallbackData.modelList());

        MenuResponse response = controller.handle(CallbackData.modelPick(1));

        assertEquals("glm-5.3", settings.model());
        assertEquals("Model: glm-5.3", response.toast());

        assertEquals("glm-5.3", AppSettings.from(AppConfig.load(settings.file())).model());
    }

    @Test
    void staleModelIndexBreaksNothing() {
        controller.handle(CallbackData.modelList());

        MenuResponse response = controller.handle(CallbackData.modelPick(99));

        assertEquals("The list is stale, open it again", response.toast());
        assertEquals("gpt-oss:120b", settings.model());
    }

    @Test
    void unavailableOllamaDoesNotBreakScreen() {
        catalog.set(null);

        MenuScreen screen = controller.handle(CallbackData.modelList()).screen();

        assertNotNull(screen);
        assertTrue(screen.text().contains("Failed to fetch the model list"), screen.text());
    }

    @Test
    void usernameListWithDeleteButtons() {
        settings.setAllowedUsernames(List.of("qateralong", "second"));

        MenuScreen screen = controller.handle(CallbackData.userList()).screen();

        assertTrue(keyboard(screen).contains("🗑 @qateralong"));
        assertTrue(keyboard(screen).contains("set:user:rm:0"));
        assertTrue(keyboard(screen).contains("set:user:rm:1"));
        assertTrue(keyboard(screen).contains("set:user:add"));
    }

    @Test
    void usernameRemovalAppliesAndPersists() {
        settings.setAllowedUsernames(List.of("qateralong", "second"));

        MenuResponse response = controller.handle(CallbackData.userRemove(1));

        assertEquals(List.of("qateralong"), settings.allowedUsernames());
        assertEquals("Removed @second", response.toast());
    }

    @Test
    void addingUsernameAsksForText() {
        MenuResponse response = controller.handle(CallbackData.userAdd());

        assertNotNull(response.inputRequest());
        assertEquals("telegram.allowed_usernames", response.inputRequest().fieldKey());
        assertTrue(response.screen().text().contains("in the next message"));
    }

    @Test
    void sentUsernameIsAddedToList() {
        InputRequest request = controller.handle(CallbackData.userAdd()).inputRequest();

        InputOutcome outcome = request.handler().accept("@Second");

        assertTrue(outcome.accepted());
        assertEquals(List.of("qateralong", "second"), settings.allowedUsernames());
        assertEquals(CallbackData.userList(), outcome.returnTo());
    }

    @Test
    void garbageInsteadOfUsernameIsRejectedAndWaitingContinues() {
        InputRequest request = controller.handle(CallbackData.userAdd()).inputRequest();

        InputOutcome outcome = request.handler().accept("this is not a username!");

        assertFalse(outcome.accepted());
        assertTrue(outcome.message().contains("does not look like a Telegram username"));
        assertEquals(List.of("qateralong"), settings.allowedUsernames());
    }

    @Test
    void repeatedAddDoesNotDuplicate() {
        InputRequest request = controller.handle(CallbackData.userAdd()).inputRequest();

        InputOutcome outcome = request.handler().accept("QaterAlong");

        assertTrue(outcome.accepted());
        assertTrue(outcome.message().contains("is already on the list"));
        assertEquals(List.of("qateralong"), settings.allowedUsernames());
    }

    @Test
    void hintsToggleFlipsAndPersists() {
        MenuResponse on = controller.handle(CallbackData.hints(true));

        assertTrue(settings.proactiveHints());
        assertEquals("Hints on", on.toast());
        assertTrue(on.screen().text().contains("Hints: on"));

        controller.handle(CallbackData.hints(false));
        assertFalse(settings.proactiveHints());
        assertFalse(AppSettings.from(AppConfig.load(settings.file())).proactiveHints());
    }

    @Test
    void hintsButtonCarriesDesiredState() {

        settings.setProactiveHints(true);

        controller.handle(CallbackData.hints(true));

        assertTrue(settings.proactiveHints());
    }

    @Test
    void typingIndicatorAndLivelyStyleAreTwoDifferentToggles() {

        assertTrue(settings.typingIndicator());
        assertFalse(settings.liveReplies());

        MenuResponse off = controller.handle(CallbackData.typingIndicator(false));
        assertFalse(settings.typingIndicator());
        assertFalse(settings.liveReplies(), "lively style untouched");
        assertEquals("\"Typing...\" off", off.toast());
        assertTrue(off.screen().text().contains("\"Typing...\" indicator: off"));
        assertTrue(off.screen().text().contains("Several short messages: off"));
        assertFalse(AppSettings.from(AppConfig.load(settings.file())).typingIndicator(), "written to file");

        controller.handle(CallbackData.liveReplies(true));
        assertTrue(settings.liveReplies());
        assertFalse(settings.typingIndicator(), "indicator untouched");
    }

    @Test
    void voiceInputToggleIsSeparateFromVoiceReplies() {

        assertTrue(settings.voiceInput());
        assertFalse(settings.voiceReplies());

        MenuResponse off = controller.handle(CallbackData.voiceInput(false));
        assertFalse(settings.voiceInput());
        assertFalse(settings.voiceReplies(), "voice replies untouched");
        assertEquals("Voice messages off", off.toast());
        assertTrue(off.screen().text().contains("Voice messages from you: off"));
        assertFalse(AppSettings.from(AppConfig.load(settings.file())).voiceInput(), "written to file");

        controller.handle(CallbackData.voiceInput(true));
        assertTrue(settings.voiceInput());
    }

    @Test
    void languageButtonSwitchesTheInterfaceAndIsWrittenToFile() {
        try {
            assertEquals(com.bebebe.agent.i18n.Language.EN, settings.language());
            assertTrue(controller.handle(CallbackData.section(MenuSection.SETTINGS))
                    .screen().text().contains("Language: English"));

            MenuResponse ru = controller.handle(CallbackData.language("ru"));

            assertEquals(com.bebebe.agent.i18n.Language.RU, settings.language());
            assertEquals("Language: Русский", ru.toast());

            assertTrue(ru.screen().text().contains("🌐 Язык: Русский"),
                    "the screen itself must already be Russian: " + ru.screen().text());
            assertEquals(com.bebebe.agent.i18n.Language.RU,
                    AppSettings.from(AppConfig.load(settings.file())).language(), "written to file");
        } finally {

            settings.setLanguage(com.bebebe.agent.i18n.Language.EN);
        }
    }

    @Test
    void allSettingsButtonsFitTheLimit() {
        List<CallbackData> all = List.of(
                CallbackData.modelList(),
                CallbackData.modelPick(SettingsScreens.MAX_MODELS - 1),
                CallbackData.userList(),
                CallbackData.userAdd(),
                CallbackData.userRemove(99),
                CallbackData.hints(true),
                CallbackData.hints(false),
                CallbackData.liveReplies(true),
                CallbackData.typingIndicator(false),
                CallbackData.voiceInput(true),
                CallbackData.language("ru"));

        for (CallbackData data : all) {
            assertTrue(data.byteSize() <= CallbackData.WARN_BYTES,
                    data + " takes " + data.byteSize() + " bytes");
        }
    }

    @Test
    void unknownSettingsButtonDoesNotBreakController() {
        assertEquals("Unknown button", controller.handle(CallbackData.of("set", "wat")).toast());
        assertEquals("Unknown button", controller.handle(CallbackData.of("set", "hints", "maybe")).toast());
        assertNull(controller.handle(CallbackData.of("set", "wat")).screen());
    }
}
