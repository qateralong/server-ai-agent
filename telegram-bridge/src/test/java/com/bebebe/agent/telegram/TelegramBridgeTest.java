package com.bebebe.agent.telegram;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.MessageSource;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.api.TelegramApi;
import com.bebebe.agent.telegram.menu.CallbackData;
import com.bebebe.agent.telegram.menu.MenuSection;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramBridgeTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long CHAT = 555L;
    private static final String ALLOWED = "QaterAlong";

    private TelegramStubServer stub;
    private TelegramBridge bridge;
    private AgentSwitch agentSwitch;
    private AppSettings settings;
    private ScriptLibrary library;
    private com.bebebe.agent.memory.MemoryStore memory;

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path temp;

    private final List<UserMessage> agentCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger updateIds = new AtomicInteger(1);

    @BeforeEach
    void setUp() throws IOException {
        stub = new TelegramStubServer();
        agentSwitch = new AgentSwitch(false);
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        settings = AppSettings.from(AppConfig.fromToml("""
                [ollama]
                model = "gpt-oss:120b"
                [telegram]
                allowed_usernames = ["%s"]
                """.formatted(ALLOWED.toLowerCase(java.util.Locale.ROOT))));
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
        library.close();
        memory.close();
        stub.close();
    }

    private void startBridge() {
        TelegramConfig config = new TelegramConfig(
                true, TelegramStubServer.TOKEN,
                Set.of(ALLOWED), Set.of(), Duration.ofSeconds(1));

        bridge = new TelegramBridge(config, agentSwitch, message -> {
            agentCalls.add(message);
            return AgentReply.text("Agent reply to: " + message.text());
        }, settings, () -> List.of("gpt-oss:120b", "glm-5.3"),
                library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS,
                new TelegramApi(TelegramStubServer.TOKEN, stub.baseUrl()));
        bridge.start();
        assertTrue(stub.awaitCalls("getMe", 1, WAIT), "The bridge did not identify with Telegram");
    }

    private void sendText(String text, String username) {
        stub.enqueue("""
                {"update_id":%d,"message":{"message_id":%d,"date":0,
                 "from":{"id":777,"is_bot":false,"first_name":"Иван","username":"%s"},
                 "chat":{"id":%d,"type":"private"},
                 "text":"%s"}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), username, CHAT, text));
    }

    private void sendVoice(String username) {
        stub.enqueue("""
                {"update_id":%d,"message":{"message_id":%d,"date":0,
                 "from":{"id":777,"is_bot":false,"first_name":"Иван","username":"%s"},
                 "chat":{"id":%d,"type":"private"},
                 "voice":{"file_id":"abc","duration":3,"mime_type":"audio/ogg"}}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), username, CHAT));
    }

    private void pressButton(String callbackData, String username) {
        stub.enqueue("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":777,"is_bot":false,"first_name":"Иван","username":"%s"},
                 "message":{"message_id":900,"date":0,"chat":{"id":%d,"type":"private"}},
                 "data":"%s"}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), username, CHAT, callbackData));
    }

    private static String textOf(JsonNode call) {
        return call.path("text").asText("");
    }

    @Test
    void disabledAgentRepliesThatItIsOffAndDoesNotCallModel() {
        startBridge();

        sendText("Привет", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT), "The bridge did not reply");
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("is off"));
        assertTrue(agentCalls.isEmpty(), "A disabled agent must not receive messages");
    }

    @Test
    void enabledAgentRepliesViaModel() {
        agentSwitch.turnOn();
        startBridge();

        sendText("Привет", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertEquals(List.of("Привет"), agentCalls.stream().map(UserMessage::text).toList());
        assertEquals(MessageSource.TELEGRAM, agentCalls.getFirst().source());
        assertEquals(String.valueOf(CHAT), agentCalls.getFirst().replyTo());
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("Agent reply to: Привет"));
    }

    @Test
    void modelReplyGoesWithoutParseMode() {

        agentSwitch.turnOn();
        startBridge();

        sendText("вопрос", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertFalse(stub.calls("sendMessage").getFirst().has("parse_mode"));
    }

    @Test
    void voiceWithDisabledAgentGetsSameReply() {
        startBridge();

        sendVoice(ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("is off"));
    }

    @Test
    void voiceWithEnabledAgentReportsSttNotReady() {
        agentSwitch.turnOn();
        startBridge();

        sendVoice(ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("Voice messages"));
        assertTrue(agentCalls.isEmpty());
    }

    @Test
    void strangerIsIgnored() {
        startBridge();

        sendText("Привет", "stranger");
        sendText("Привет", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertFalse(stub.awaitCalls("sendMessage", 2, Duration.ofMillis(700)),
                "The bridge replied to a stranger");
    }

    @Test
    void startCommandSendsRootMenu() {
        startBridge();

        sendText("/start", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        JsonNode sent = stub.calls("sendMessage").getFirst();
        assertTrue(textOf(sent).contains("Choose a section"));

        String keyboard = sent.path("reply_markup").toString();
        for (MenuSection section : MenuSection.values()) {
            if (section == MenuSection.LOGS) {
                continue;
            }
            assertTrue(keyboard.contains(CallbackData.section(section).encode()),
                    "Menu has no button " + section);
        }
    }

    @Test
    void menuCommandGivesTheSame() {
        startBridge();

        sendText("/menu", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("Choose a section"));
    }

    @Test
    void openingSectionEditsSameMessageInsteadOfSendingNew() {
        startBridge();

        pressButton(CallbackData.section(MenuSection.NOTES).encode(), ALLOWED);

        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT), "The section did not open by editing the message");
        JsonNode edit = stub.calls("editMessageText").getFirst();
        assertEquals(900, edit.path("message_id").asInt());
        assertTrue(textOf(edit).contains("Notes"));
        assertTrue(stub.calls("sendMessage").isEmpty(), "Menu navigation must not send a new message");
    }

    @Test
    void powerButtonEnablesAgentFromChat() {
        startBridge();
        assertFalse(agentSwitch.isOn());

        pressButton(CallbackData.power(true).encode(), ALLOWED);

        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT));
        assertTrue(agentSwitch.isOn(), "The Telegram button did not toggle the internal flag");
        assertTrue(textOf(stub.calls("editMessageText").getFirst()).contains("🟢 on"));
    }

    @Test
    void alwaysAnswersCallbackQueryOnPress() {

        startBridge();

        pressButton(CallbackData.root().encode(), ALLOWED);

        assertTrue(stub.awaitCalls("answerCallbackQuery", 1, WAIT));
    }

    @Test
    void strangerPressIsRefused() {
        startBridge();

        pressButton(CallbackData.power(true).encode(), "stranger");

        assertTrue(stub.awaitCalls("answerCallbackQuery", 1, WAIT));
        assertEquals("Access denied", stub.calls("answerCallbackQuery").getFirst().path("text").asText());
        assertFalse(agentSwitch.isOn(), "A stranger enabled the agent");
        assertTrue(stub.calls("editMessageText").isEmpty());
    }

    @Test
    void brokenCallbackDataDoesNotBreakBridge() {
        startBridge();

        pressButton("not-our-format:::", ALLOWED);
        pressButton(CallbackData.root().encode(), ALLOWED);

        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT));
    }

    @Test
    void switchingFromGuiRedrawsOpenMenuInTelegram() {
        startBridge();

        pressButton(CallbackData.section(MenuSection.POWER).encode(), ALLOWED);
        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT));
        assertTrue(textOf(stub.calls("editMessageText").getFirst()).contains("🔴 off"));

        agentSwitch.turnOn();

        assertTrue(stub.awaitCalls("editMessageText", 2, WAIT),
                "The Telegram menu did not update after switching from the GUI");
        assertTrue(textOf(stub.calls("editMessageText").get(1)).contains("🟢 on"));
    }

    @Test
    void cancelCommandReportsNothingToCancel() {
        startBridge();

        sendText("/cancel", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("Nothing to cancel"));
    }

    @Test
    void pendingInputInterceptsNextMessage() {
        agentSwitch.turnOn();
        startBridge();

        List<String> captured = new CopyOnWriteArrayList<>();
        bridge.pendingInputs().await(CHAT, com.bebebe.agent.telegram.input.PendingInput.of(
                "ollama.model", "Send the model name", 900,
                value -> {
                    captured.add(value);
                    return com.bebebe.agent.telegram.input.InputOutcome.accepted(
                            "Accepted: " + value, CallbackData.section(MenuSection.SETTINGS));
                }));

        sendText("qwen3.5:cloud", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertEquals(List.of("qwen3.5:cloud"), captured);
        assertTrue(agentCalls.isEmpty(), "The answer message must not go to the agent");
        assertTrue(textOf(stub.calls("sendMessage").getFirst()).contains("Accepted"));
        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT), "The menu did not return to the right screen");
    }

    @Test
    void settingsSectionOpensAndHidesSecrets() {
        settings.setTelegramBotToken("111:SUPER-SECRET");
        startBridge();

        pressButton(CallbackData.section(MenuSection.SETTINGS).encode(), ALLOWED);

        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT));
        String text = textOf(stub.calls("editMessageText").getFirst());
        assertTrue(text.contains("Model"));
        assertFalse(text.contains("SUPER-SECRET"), text);
    }

    @Test
    void settingsChangeFromGuiUpdatesOpenChatSection() {
        startBridge();
        pressButton(CallbackData.section(MenuSection.SETTINGS).encode(), ALLOWED);
        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT));
        assertTrue(textOf(stub.calls("editMessageText").getFirst()).contains("gpt-oss:120b"));

        settings.setModel("glm-5.3");

        assertTrue(stub.awaitCalls("editMessageText", 2, WAIT),
                "The Telegram section did not update after the GUI change");
        assertTrue(textOf(stub.calls("editMessageText").get(1)).contains("glm-5.3"));
    }

    @Test
    void allowListChangeAppliesImmediatelyWithoutRestart() {
        startBridge();

        settings.setAllowedUsernames(List.of("someone-else"));
        sendText("Привет", ALLOWED);

        assertFalse(stub.awaitCalls("sendMessage", 1, Duration.ofSeconds(2)),
                "The bridge replied to a user removed from the allow-list");
    }

    @Test
    void addingUsernameViaPendingInputEndToEnd() {
        startBridge();

        pressButton(CallbackData.userAdd().encode(), ALLOWED);
        assertTrue(stub.awaitCalls("editMessageText", 1, WAIT));
        assertTrue(textOf(stub.calls("editMessageText").getFirst()).contains("in the next message"));

        sendText("@NewFriend", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(settings.allowedUsernames().contains("newfriend"),
                "username not added: " + settings.allowedUsernames());
        assertTrue(agentCalls.isEmpty(), "The answer to a value request must not go to the agent");
    }

    @Test
    void afterInputModeIsClearedAndMessagesGoToAgentAgain() {
        agentSwitch.turnOn();
        startBridge();

        bridge.pendingInputs().await(CHAT, com.bebebe.agent.telegram.input.PendingInput.of(
                "field", "Send the value", 900,
                value -> com.bebebe.agent.telegram.input.InputOutcome.accepted("ok")));

        sendText("значение", ALLOWED);
        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));

        sendText("обычный вопрос", ALLOWED);
        assertTrue(stub.awaitCalls("sendMessage", 2, WAIT));
        assertEquals(List.of("обычный вопрос"), agentCalls.stream().map(UserMessage::text).toList());
    }

    private java.nio.file.Path fakeOgg() {
        try {
            java.nio.file.Path ogg = java.nio.file.Files.createTempFile(temp, "reply", ".ogg");
            java.nio.file.Files.writeString(ogg, "OggS-fake-opus");
            return ogg;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void withToggleOnVoiceReplyGoesAsTextAndVoice() throws Exception {
        startBridge();
        List<String> spoken = new CopyOnWriteArrayList<>();
        bridge.attachTts(text -> {
            spoken.add(text);
            return java.util.Optional.of(fakeOgg());
        }, false);
        settings.setVoiceReplies(true);

        bridge.deliver(CHAT, AgentReply.text("Напомню в **17:00**."), true);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT), "the text must go");
        assertTrue(stub.awaitCalls("sendVoice", 1, WAIT), "followed by the voice");
        JsonNode voice = stub.calls("sendVoice").getFirst();
        assertEquals(String.valueOf(CHAT), voice.path("chat_id").asText());
        assertEquals("audio/ogg", voice.path("voice_type").asText());
        assertEquals("OggS-fake-opus".length(), voice.path("voice_bytes").asInt());
        assertEquals(List.of("Напомню в **17:00**."), spoken, "TTS cleans the text itself, the bridge passes it as is");
    }

    @Test
    void withToggleOffTextOnly() throws Exception {
        startBridge();
        bridge.attachTts(text -> java.util.Optional.of(fakeOgg()), false);
        settings.setVoiceReplies(false);

        bridge.deliver(CHAT, AgentReply.text("Ответ"), true);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertFalse(stub.awaitCalls("sendVoice", 1, Duration.ofMillis(700)));
    }

    @Test
    void replyToTextMessageIsNotVoicedWithoutReplyToText() throws Exception {
        startBridge();
        bridge.attachTts(text -> java.util.Optional.of(fakeOgg()), false);
        settings.setVoiceReplies(true);
        agentSwitch.turnOn();

        sendText("Привет", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertFalse(stub.awaitCalls("sendVoice", 1, Duration.ofMillis(700)), "asked in text -- answered in text");
    }

    @Test
    void withReplyToTextTextReplyIsVoicedToo() throws Exception {
        startBridge();
        bridge.attachTts(text -> java.util.Optional.of(fakeOgg()), true);
        settings.setVoiceReplies(true);
        agentSwitch.turnOn();

        sendText("Привет", ALLOWED);

        assertTrue(stub.awaitCalls("sendVoice", 1, WAIT));
    }

    @Test
    void synthesisFailureDoesNotBreakTextReply() throws Exception {
        startBridge();
        bridge.attachTts(text -> java.util.Optional.empty(), false);
        settings.setVoiceReplies(true);

        bridge.deliver(CHAT, AgentReply.text("Ответ"), true);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertFalse(stub.awaitCalls("sendVoice", 1, Duration.ofMillis(700)));
    }

    @Test
    void messagesGoInOrderWithTypingIndicatorAndSingleVoice() throws Exception {
        startBridge();
        bridge.attachTts(text -> java.util.Optional.of(fakeOgg()), false);
        settings.setVoiceReplies(true);
        long started = System.nanoTime();

        bridge.deliver(CHAT, AgentReply.parts(List.of("Первая мысль.", "Вторая, чуть длиннее первой.")), true);

        assertTrue(stub.awaitCalls("sendMessage", 2, WAIT), "both messages must arrive");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs >= TelegramBridge.TYPING_MIN_MS, "there was a typing pause before the second message: " + elapsedMs + " мс");
        List<String> texts = stub.calls("sendMessage").stream().map(c -> c.path("text").asText()).toList();
        assertEquals(List.of("Первая мысль.", "Вторая, чуть длиннее первой."), texts, "order preserved");
        assertEquals(1, stub.calls("sendChatAction").size(), "typing only between messages, the first goes immediately");
        assertTrue(stub.calls("sendChatAction").stream().allMatch(c -> c.path("action").asText().equals("typing")));
        assertTrue(stub.awaitCalls("sendVoice", 1, WAIT));
        assertFalse(stub.awaitCalls("sendVoice", 2, Duration.ofMillis(700)), "one voice message per reply, not per part");
    }

    @Test
    void withoutIndicatorMessagesGoWithoutTypingButInOrder() throws Exception {
        startBridge();
        settings.setTypingIndicator(false);

        bridge.deliver(CHAT, AgentReply.parts(List.of("Раз.", "Два.")), false);

        assertTrue(stub.awaitCalls("sendMessage", 2, WAIT));
        List<String> texts = stub.calls("sendMessage").stream().map(c -> c.path("text").asText()).toList();
        assertEquals(List.of("Раз.", "Два."), texts);
        assertTrue(stub.calls("sendChatAction").isEmpty(), "toggle off -- not a single sendChatAction");
    }

    @Test
    void singleMessageGoesAsBeforeWithoutIndicator() throws Exception {
        startBridge();

        bridge.deliver(CHAT, AgentReply.text("Коротко."), false);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(stub.calls("sendChatAction").isEmpty());
    }

    @Test
    void whileAgentThinksChatShowsTypingEvenWithoutLivelyStyle() {
        startBridge();
        agentSwitch.turnOn();

        sendText("Подумай", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertTrue(stub.awaitCalls("sendChatAction", 1, WAIT), "typing between the question and the first message");
        assertEquals("typing", stub.calls("sendChatAction").getFirst().path("action").asText());
        assertTrue(stub.calls("sendChatAction").getFirst().path("chat_id").asLong() == CHAT);
    }

    @Test
    void disabledIndicatorNeverSendsTyping() {
        startBridge();
        agentSwitch.turnOn();
        settings.setTypingIndicator(false);

        sendText("Подумай", ALLOWED);

        assertTrue(stub.awaitCalls("sendMessage", 1, WAIT));
        assertFalse(stub.awaitCalls("sendChatAction", 1, Duration.ofMillis(500)));
    }

    @Test
    void typingDurationIsProportionalToLengthWithCeiling() {
        assertEquals(TelegramBridge.TYPING_MIN_MS, TelegramBridge.typingDelayMs("да"));
        assertEquals(60 * TelegramBridge.TYPING_MS_PER_CHAR, TelegramBridge.typingDelayMs("x".repeat(60)));
        assertEquals(TelegramBridge.TYPING_MAX_MS, TelegramBridge.typingDelayMs("x".repeat(5000)));
    }
}
