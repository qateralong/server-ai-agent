package com.bebebe.agent.telegram;

import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.config.SettingsField;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.core.AgentState;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.api.Dto.CallbackQuery;
import com.bebebe.agent.telegram.api.Dto.Message;
import com.bebebe.agent.telegram.api.Dto.Update;
import com.bebebe.agent.telegram.api.TelegramApi;
import com.bebebe.agent.telegram.api.TelegramApiException;
import com.bebebe.agent.telegram.input.InputOutcome;
import com.bebebe.agent.telegram.input.PendingInput;
import com.bebebe.agent.telegram.input.PendingInputs;
import com.bebebe.agent.telegram.menu.CallbackData;
import com.bebebe.agent.telegram.menu.ConfirmScreens;
import com.bebebe.agent.telegram.menu.MemoryScreens;
import com.bebebe.agent.telegram.menu.InputRequest;
import com.bebebe.agent.telegram.menu.MenuController;
import com.bebebe.agent.telegram.menu.MenuRenderer;
import com.bebebe.agent.telegram.menu.MenuSection;
import com.bebebe.agent.telegram.menu.MenuResponse;
import com.bebebe.agent.telegram.menu.MenuScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TelegramBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelegramBridge.class);

    private static final Duration RETRY_MIN = Duration.ofSeconds(3);
    private static final Duration RETRY_MAX = Duration.ofMinutes(2);

    private static final String OFF_REPLY = """
            🔴 The agent is off -- messages are not processed.

            Switch on: /menu -> ⚡ Power (or the button in the application window).""";

    private final AgentSwitch agentSwitch;
    private final AgentTextHandler agentHandler;
    private final AppSettings settings;
    private final AccessControl access;
    private final MenuController menu;
    private final PendingInputs pendingInputs = new PendingInputs();

    private volatile TelegramConfig config;
    private volatile TelegramApi api;

    private final Map<Long, OpenMenu> openMenus = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Consumer<AgentState> stateListener = this::onAgentStateChanged;
    private final Consumer<SettingsField> settingsListener = this::onSettingsChanged;

    private Thread pollThread;
    private ExecutorService outbound;
    private volatile String botUsername = "";
    private volatile long offset;

    private volatile Long lastChatId;

    public TelegramBridge(TelegramConfig config,
                          AgentSwitch agentSwitch,
                          AgentTextHandler agentHandler,
                          AppSettings settings,
                          Supplier<List<String>> modelCatalog,
                          ScriptLibrary library,
                          MenuController.ConfirmationActions confirmActions,
                          MemoryStore memory,
                          MenuController.MemoryActions memoryActions) {
        this(config, agentSwitch, agentHandler, settings, modelCatalog, library, confirmActions,
                memory, memoryActions, new TelegramApi(config.botToken()));
    }

    public TelegramBridge(TelegramConfig config,
                          AgentSwitch agentSwitch,
                          AgentTextHandler agentHandler,
                          AppSettings settings,
                          Supplier<List<String>> modelCatalog,
                          ScriptLibrary library,
                          MenuController.ConfirmationActions confirmActions,
                          MemoryStore memory,
                          MenuController.MemoryActions memoryActions,
                          TelegramApi api) {
        this.config = config;
        this.agentSwitch = agentSwitch;
        this.agentHandler = agentHandler;
        this.settings = settings;
        this.api = api;
        this.access = config.accessControl();
        this.menu = new MenuController(agentSwitch, settings, modelCatalog, library, confirmActions,
                memory, memoryActions);
    }

    public String name() {
        return "telegram-bridge";
    }

    public boolean isReady() {
        return running.get();
    }

    public PendingInputs pendingInputs() {
        return pendingInputs;
    }

    public void attachJobs(com.bebebe.agent.scheduler.JobStore jobs, java.time.ZoneId zone) {
        menu.attachJobs(jobs, zone);
    }

    public void attachNotes(com.bebebe.agent.notes.NotesStore notes) {
        menu.attachNotes(notes);
    }

    public void attachWindow(Runnable showWindow) {
        menu.attachWindow(showWindow);
    }

    public void attachHeadless(java.util.function.Supplier<com.bebebe.agent.telegram.menu.ServerStatus> status,
                               com.bebebe.agent.logging.LogBuffer logs, String logDir) {
        menu.attachHeadless(status, logs, logDir);
    }

    public void attachPersonas(com.bebebe.agent.memory.PersonaStore personas) {
        menu.attachPersonas(personas);
    }

    private volatile java.util.function.Function<String, Optional<java.nio.file.Path>> speaker;

    private volatile boolean speakTextReplies;

    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-tts");
        t.setDaemon(true);
        return t;
    });

    public void attachTts(java.util.function.Function<String, Optional<java.nio.file.Path>> speaker,
                          boolean speakTextReplies) {
        this.speaker = speaker;
        this.speakTextReplies = speakTextReplies;
    }

    private volatile VoiceInput voiceInput;

    public void attachVoiceInput(VoiceInput voiceInput) {
        this.voiceInput = voiceInput;
    }

    public Optional<Long> lastChatId() {
        return Optional.ofNullable(lastChatId);
    }

    public record BackupOutcome(java.nio.file.Path archive, String summary) { }

    private volatile java.util.function.Supplier<BackupOutcome> backup;

    public void attachBackup(java.util.function.Supplier<BackupOutcome> backup) {
        this.backup = backup;
    }

    private void sendBackupLater(long chatId) {
        var make = backup;
        ttsExecutor.submit(TraceContext.wrap(TraceContext.current().orElse(null), () -> {
            try {
                BackupOutcome outcome = make.get();
                api.sendDocument(chatId, outcome.archive(), outcome.summary());
                log.atInfo().addKeyValue("event", "backup.sent").addKeyValue("chat_id", chatId)
                        .log("Backup sent to chat {}", chatId);
            } catch (RuntimeException e) {
                log.error("Backup not sent", e);
                send(chatId, "⚠️ Backup failed: " + e.getMessage());
            }
        }));
    }

    static final long TYPING_REFRESH_MS = 4000;
    private final java.util.concurrent.ScheduledExecutorService typingExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "telegram-typing");
                t.setDaemon(true);
                return t;
            });

    private AutoCloseable typingWhile(long chatId) {
        if (!settings.typingIndicator()) {
            return () -> { };
        }

        Runnable typing = () -> {
            try {
                api.sendChatAction(chatId, "typing");
            } catch (TelegramApiException e) {
                log.debug("sendChatAction failed: {}", e.getMessage());
            }
        };
        typing.run();
        var task = typingExecutor.scheduleAtFixedRate(TraceContext.wrap(TraceContext.current().orElse(null), typing),
                TYPING_REFRESH_MS, TYPING_REFRESH_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }

    static final long TYPING_MIN_MS = 600;
    static final long TYPING_MAX_MS = 4000;
    static final long TYPING_MS_PER_CHAR = 30;

    static final long BETWEEN_MS = 400;

    static long typingDelayMs(String part) {
        return Math.max(TYPING_MIN_MS, Math.min(TYPING_MAX_MS, part.length() * TYPING_MS_PER_CHAR));
    }

    private void deliverLively(long chatId, AgentReply.Text text, boolean speak) {
        ttsExecutor.submit(TraceContext.wrap(TraceContext.current().orElse(null), () -> {
            java.util.List<String> parts = text.parts();
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                if (i > 0) {
                    sleep(Duration.ofMillis(BETWEEN_MS));
                    if (settings.typingIndicator()) {
                        try {
                            api.sendChatAction(chatId, "typing");
                        } catch (TelegramApiException e) {
                            log.debug("sendChatAction failed: {}", e.getMessage());
                        }
                        sleep(Duration.ofMillis(typingDelayMs(part)));
                    }
                }
                sendPlain(chatId, part);
                log.atInfo()
                        .addKeyValue("event", "reply.sent")
                        .addKeyValue("chat_id", chatId)
                        .addKeyValue("part", i + 1)
                        .addKeyValue("parts", parts.size())
                        .addKeyValue("length", part.length())
                        .log("Message {}/{} sent to chat {}", i + 1, parts.size(), chatId);
            }
            if (speak) {
                speakNow(chatId, text.text());
            }
        }));
    }

    private void speakLater(long chatId, String text) {
        ttsExecutor.submit(TraceContext.wrap(TraceContext.current().orElse(null), () -> speakNow(chatId, text)));
    }

    private void speakNow(long chatId, String text) {
        var speak = speaker;
        if (speak == null) {
            return;
        }
        Optional<java.nio.file.Path> ogg = speak.apply(text);
        if (ogg.isEmpty()) {
            return;
        }
        try {
            api.sendVoice(chatId, ogg.get());
            log.atInfo().addKeyValue("event", "voice.sent").addKeyValue("chat_id", chatId)
                    .log("Voice reply sent to chat {}", chatId);
        } catch (TelegramApiException e) {
            log.warn("Cannot send voice to chat {}: {}", chatId, e.getMessage());
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(ogg.get());
            } catch (java.io.IOException ignored) {

            }
        }
    }

    public void sendTo(long chatId, String text) {
        sendPlain(chatId, text);
    }

    public void start() {
        if (!config.isUsable()) {
            log.info("Telegram bridge not started: {}",
                    config.enabled() ? "telegram.bot_token is not set" : "disabled in config");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        access.logSummary();

        outbound = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telegram-outbound");
            thread.setDaemon(true);
            return thread;
        });
        agentSwitch.addListener(stateListener);
        settings.addListener(settingsListener);

        pollThread = new Thread(this::pollLoop, "telegram-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        agentSwitch.removeListener(stateListener);
        settings.removeListener(settingsListener);
        if (pollThread != null) {
            pollThread.interrupt();
        }
        if (outbound != null) {
            outbound.shutdownNow();
        }
        log.info("Telegram bridge stopped");
    }

    @Override
    public void close() {
        stop();
        api.close();
    }

    public synchronized void reconnect(TelegramConfig updated) {
        log.info("Reconnecting Telegram bridge: token changed");
        boolean wasRunning = running.get();
        if (wasRunning) {
            stop();
        }

        TelegramApi previous = api;
        this.config = updated;
        this.api = new TelegramApi(updated.botToken());
        this.offset = 0;
        previous.close();

        access.updateUsernames(updated.allowedUsernames());
        if (wasRunning || updated.isUsable()) {
            openMenus.clear();
            start();
        }
    }

    private void pollLoop() {
        try {
            botUsername = api.getMe().username();
            log.info("Telegram bridge started: @{}", botUsername);
        } catch (RuntimeException e) {

            log.error("Failed to identify with Telegram (check telegram.bot_token): {}", e.getMessage());
        }

        Duration backoff = RETRY_MIN;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<Update> updates = api.getUpdates(offset, config.pollTimeout());
                for (Update update : updates) {
                    offset = Math.max(offset, update.updateId() + 1);
                    dispatchSafely(update);
                }
                backoff = RETRY_MIN;
            } catch (RuntimeException e) {
                if (!running.get()) {
                    break;
                }
                log.warn("Polling failure, retry in {} s: {}", backoff.toSeconds(), e.getMessage());
                if (!sleep(backoff)) {
                    break;
                }
                backoff = backoff.multipliedBy(2).compareTo(RETRY_MAX) > 0 ? RETRY_MAX : backoff.multipliedBy(2);
            }
        }
        log.debug("Polling loop finished");
    }

    private void dispatchSafely(Update update) {
        try (TraceContext.Scope ignored = TraceContext.openNew()) {
            log.atDebug()
                    .addKeyValue("event", "update.received")
                    .addKeyValue("update_id", update.updateId())
                    .addKeyValue("kind", update.callbackQuery() != null ? "callback" : "message")
                    .log("Update {}", update.updateId());
            if (update.callbackQuery() != null) {
                onCallback(update.callbackQuery());
            } else if (update.message() != null) {
                onMessage(update.message());
            }
        } catch (RuntimeException e) {
            log.error("Error handling update {}", update.updateId(), e);
        }
    }

    private void onMessage(Message message) {
        if (message.chat() == null) {
            return;
        }
        long chatId = message.chat().id();

        if (!access.isAllowed(message.from(), message.chat())) {

            log.warn("Rejected message from {} in chat {}",
                    message.from() == null ? "<unknown>" : message.from().describe(), chatId);
            return;
        }
        if (lastChatId == null || lastChatId != chatId) {

            log.info("Message accepted: {} in chat {}",
                    message.from() == null ? "<unknown>" : message.from().describe(), chatId);
        }
        lastChatId = chatId;

        Optional<PendingInput> pending = pendingInputs.peek(chatId);
        if (pending.isPresent() && message.hasText() && !message.text().startsWith("/")) {
            handlePendingInput(chatId, pendingInputs.consume(chatId).orElseThrow(), message.text());
            return;
        }

        if (pending.isPresent() && message.hasDocument()) {
            onPendingDocument(chatId, message.document());
            return;
        }

        String command = message.command();
        if (!command.isEmpty()) {
            handleCommand(chatId, command);
            return;
        }

        if (message.isVoice()) {
            onVoiceMessage(chatId, message);
            return;
        }

        if (!message.hasText()) {
            return;
        }
        if (!agentSwitch.isOn()) {
            send(chatId, OFF_REPLY);
            return;
        }
        replyAsAgent(chatId, message.text());
    }

    /** A long instruction is easier to send as a file than to paste into the chat. */
    static final long MAX_TEXT_FILE_BYTES = 256 * 1024;

    private void onPendingDocument(long chatId, com.bebebe.agent.telegram.api.Dto.Document document) {
        if (!document.isPlainText()) {
            send(chatId, "I can only read a plain text file here (.txt or .md). "
                    + "Send the text as a message, or attach a .txt file.");
            return;
        }
        if (document.fileSize() != null && document.fileSize() > MAX_TEXT_FILE_BYTES) {
            send(chatId, "The file is too large: up to " + (MAX_TEXT_FILE_BYTES / 1024) + " KB.");
            return;
        }
        String traceId = TraceContext.current().orElse(null);
        ttsExecutor.submit(TraceContext.wrap(traceId, () -> readDocumentAsInput(chatId, document)));
    }

    private void readDocumentAsInput(long chatId, com.bebebe.agent.telegram.api.Dto.Document document) {
        String text;
        try {
            var file = api.getFile(document.fileId());
            if (file == null || file.filePath() == null || file.filePath().isBlank()) {
                send(chatId, "Telegram did not give me this file.");
                return;
            }
            byte[] bytes = api.downloadFile(file.filePath());
            if (bytes.length > MAX_TEXT_FILE_BYTES) {
                send(chatId, "The file is too large: up to " + (MAX_TEXT_FILE_BYTES / 1024) + " KB.");
                return;
            }
            text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (TelegramApiException e) {
            log.warn("File from chat {} not downloaded: {}", chatId, e.getMessage());
            send(chatId, "Could not download the file: " + e.getMessage());
            return;
        }

        if (text.isEmpty()) {
            send(chatId, "The file is empty -- nothing to take from it.");
            return;
        }

        // Consumed only now: a failed download must leave the chat still waiting for the answer.
        Optional<PendingInput> pending = pendingInputs.consume(chatId);
        if (pending.isEmpty()) {
            send(chatId, "The wait is over -- open the menu again.");
            return;
        }
        log.atInfo().addKeyValue("event", "input.file")
                .addKeyValue("chat_id", chatId)
                .addKeyValue("field", pending.get().fieldKey())
                .addKeyValue("chars", text.length())
                .log("Chat {}: '{}' taken from the file {} ({} chars)",
                        chatId, pending.get().fieldKey(), document.fileName(), text.length());
        handlePendingInput(chatId, pending.get(), text);
    }

    private void onVoiceMessage(long chatId, Message message) {
        if (!agentSwitch.isOn()) {
            send(chatId, OFF_REPLY);
            return;
        }
        if (!settings.voiceInput()) {
            send(chatId, "🎤 Voice messages are turned off. Turn them on: /menu -> \u2699\ufe0f Settings.");
            return;
        }
        if (voiceInput == null) {
            send(chatId, "🎤 Voice messages are not available: speech recognition is not set up"
                    + " (whisper.cpp in the [stt] section of the config).");
            return;
        }

        String traceId = TraceContext.current().orElse(null);
        var voice = message.voice();
        ttsExecutor.submit(TraceContext.wrap(traceId, () -> downloadAndTranscribe(chatId, voice, traceId)));
    }

    private void downloadAndTranscribe(long chatId, com.bebebe.agent.telegram.api.Dto.Voice voice, String traceId) {
        byte[] audio;
        String path;
        try {
            var file = api.getFile(voice.fileId());
            if (file == null || file.filePath() == null || file.filePath().isBlank()) {
                send(chatId, "🎤 Telegram did not give me the file of this voice message.");
                return;
            }
            if (file.fileSize() != null && file.fileSize() > TelegramApi.MAX_DOWNLOAD_BYTES) {

                send(chatId, "🎤 This voice message is too long: a bot cannot download more than 20 MB.");
                return;
            }
            path = file.filePath();
            audio = api.downloadFile(path);
        } catch (TelegramApiException e) {
            log.warn("Voice message from chat {} not downloaded: {}", chatId, e.getMessage());
            send(chatId, "🎤 Could not download the voice message: " + e.getMessage());
            return;
        }

        log.atInfo().addKeyValue("event", "voice.received")
                .addKeyValue("chat_id", chatId)
                .addKeyValue("bytes", audio.length)
                .addKeyValue("seconds", voice.duration())
                .log("Voice message from chat {}: {} bytes, {} s", chatId, audio.length, voice.duration());

        if (!voiceInput.accept(audio, extensionOf(path), traceId)) {

            send(chatId, "🎤 I could not make out the voice message. Try again or write it as text.");
        }
    }

    static String extensionOf(String filePath) {
        int slash = filePath.lastIndexOf('/');
        int dot = filePath.lastIndexOf('.');
        return dot > slash && dot < filePath.length() - 1 ? filePath.substring(dot) : ".oga";
    }

    private void handleCommand(long chatId, String command) {
        switch (command) {
            case "start", "menu" -> openMenu(chatId);
            case "cancel" -> send(chatId, pendingInputs.cancel(chatId)
                    ? "Input cancelled."
                    : "Nothing to cancel.");
            default -> send(chatId, "Unknown command. Available: /start, /menu, /cancel");
        }
    }

    private void handlePendingInput(long chatId, PendingInput pending, String value) {
        log.debug("Chat {}: accepted a value for '{}'", chatId, pending.fieldKey());
        InputOutcome outcome;
        try {
            outcome = pending.handler().accept(value);
        } catch (RuntimeException e) {
            log.error("Input handler '{}' failed", pending.fieldKey(), e);
            send(chatId, "Failed to apply the value: " + e.getMessage());
            return;
        }

        if (!outcome.accepted()) {

            pendingInputs.await(chatId, pending);
        }
        if (outcome.message() != null && !outcome.message().isBlank()) {
            send(chatId, outcome.message());
        }
        if (outcome.accepted() && outcome.returnTo() != null) {
            MenuResponse response = menu.handle(outcome.returnTo());
            if (response.screen() != null) {
                renderInto(chatId, pending.menuMessageId(), response.screen());
            }
        }
    }

    private void replyAsAgent(long chatId, String text) {
        AgentReply reply;
        try (AutoCloseable typing = typingWhile(chatId)) {

            reply = agentHandler.reply(UserMessage.telegram(text, chatId));
        } catch (Exception e) {
            log.error("The agent failed to reply", e);
            send(chatId, "⚠️ Agent error: " + e.getMessage());
            return;
        }
        deliver(chatId, reply, speakTextReplies);
    }

    public void deliver(long chatId, AgentReply reply) {
        deliver(chatId, reply, false);
    }

    public void deliver(long chatId, AgentReply reply, boolean spoken) {
        switch (reply) {
            case AgentReply.Silence ignored -> { }
            case AgentReply.Text text -> {
                if (text.text().isBlank()) {
                    break;
                }
                if (text.isMultipart()) {

                    deliverLively(chatId, text, spoken && speaker != null && settings.voiceReplies());
                    break;
                }

                sendPlain(chatId, text.text());
                log.atInfo()
                        .addKeyValue("event", "reply.sent")
                        .addKeyValue("chat_id", chatId)
                        .addKeyValue("length", text.text().length())
                        .log("Reply sent to chat {}", chatId);
                if (spoken && speaker != null && settings.voiceReplies()) {
                    speakLater(chatId, text.text());
                }
            }
            case AgentReply.NeedsConfirmation confirmation -> {
                MenuScreen screen = ConfirmScreens.request(
                        confirmation.script(), confirmation.description(), confirmation.token());
                try {
                    api.sendMessage(chatId, screen.text(), screen.keyboard());
                } catch (TelegramApiException e) {
                    log.error("Cannot send confirmation request to chat {}: {}", chatId, e.getMessage());
                }
            }
            case AgentReply.EntityQuestion question -> {
                MenuScreen screen = MemoryScreens.entityQuestion(
                        question.token(), question.mention(), question.candidate(), question.heldFacts());
                try {
                    api.sendMessage(chatId, screen.text(), screen.keyboard());
                } catch (TelegramApiException e) {
                    log.error("Cannot send memory question to chat {}: {}", chatId, e.getMessage());
                }
            }
        }
    }

    public void offerTrust(long chatId, com.bebebe.agent.script.library.ScriptEntry script) {
        MenuScreen screen = ConfirmScreens.trustOffer(script);
        try {
            api.sendMessage(chatId, screen.text(), screen.keyboard());
        } catch (TelegramApiException e) {
            log.debug("Cannot send trust offer: {}", e.getMessage());
        }
    }

    private void openMenu(long chatId) {
        MenuScreen screen = menu.rootScreen();
        try {
            Message sent = api.sendMessage(chatId, screen.text(), screen.keyboard());
            if (sent != null) {
                openMenus.put(chatId, new OpenMenu(sent.messageId(), screen));
            }
        } catch (TelegramApiException e) {
            log.error("Failed to send menu to chat {}: {}", chatId, e.getMessage());
        }
    }

    private void onCallback(CallbackQuery query) {
        Message message = query.message();
        if (message == null || message.chat() == null) {
            api.answerCallbackQuery(query.id(), "Message unavailable", false);
            return;
        }
        long chatId = message.chat().id();

        if (!access.isAllowed(query.from(), message.chat())) {
            log.warn("Rejected button press from {} in chat {}",
                    query.from() == null ? "<unknown>" : query.from().describe(), chatId);
            api.answerCallbackQuery(query.id(), "Access denied", true);
            return;
        }

        CallbackData data;
        try {
            data = CallbackData.decode(query.data());
        } catch (IllegalArgumentException e) {
            log.warn("Could not parse callback_data '{}': {}", query.data(), e.getMessage());
            api.answerCallbackQuery(query.id(), "Unknown button", false);
            return;
        }

        if (data.namespace().equals(CallbackData.NS_SETTINGS) && data.action().equals("backup")) {

            api.answerCallbackQuery(query.id(), backup == null ? "Backup not configured" : "Building the archive...", false);
            if (backup != null) {
                sendBackupLater(chatId);
            }
            return;
        }

        MenuResponse response;
        if (data.namespace().equals(CallbackData.NS_CONFIRM) && data.action().equals("run")) {

            try (AutoCloseable typing = typingWhile(chatId)) {
                response = menu.handle(data, "TELEGRAM:" + chatId);
            } catch (Exception e) {
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        } else {
            response = menu.handle(data, "TELEGRAM:" + chatId);
        }
        if (response.screen() != null) {
            renderInto(chatId, message.messageId(), response.screen());
        }
        if (response.inputRequest() != null) {

            InputRequest request = response.inputRequest();
            pendingInputs.await(chatId, PendingInput.of(
                    request.fieldKey(), "Waiting for a value", message.messageId(), request.handler()));
        }

        api.answerCallbackQuery(query.id(), response.toast(), false);
    }

    private void renderInto(long chatId, int messageId, MenuScreen screen) {
        try {
            api.editMessageText(chatId, messageId, screen.text(), screen.keyboard());
            if (screen.keyboard().buttonCount() == 0) {
                openMenus.remove(chatId);
            } else {
                openMenus.put(chatId, new OpenMenu(messageId, screen));
            }
        } catch (TelegramApiException e) {
            if (e.isNotModified()) {

                log.debug("Chat {}: screen unchanged", chatId);
            } else {
                log.error("Failed to redraw menu in chat {}: {}", chatId, e.getMessage());
            }
        }
    }

    private void onAgentStateChanged(AgentState state) {
        ExecutorService executor = outbound;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.execute(() -> openMenus.forEach((chatId, open) -> {
            if (!open.screen().reflectsAgentState()) {
                return;
            }
            renderInto(chatId, open.messageId(), rerender(open.screen()));
        }));
    }

    private void onSettingsChanged(SettingsField field) {
        ExecutorService executor = outbound;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        if (field == SettingsField.ALLOWED_USERNAMES) {

            access.updateUsernames(settings.allowedUsernames());
        }
        executor.execute(() -> openMenus.forEach((chatId, open) -> {
            if (open.screen().section() == MenuSection.SETTINGS || open.screen().section() == null) {
                renderInto(chatId, open.messageId(), rerender(open.screen()));
            }
        }));
    }

    private MenuScreen rerender(MenuScreen open) {
        return open.section() == null ? menu.rootScreen() : menu.screenFor(open.section());
    }

    /**
     * Every message the bridge writes on its own behalf goes through here, so this is where
     * it gets translated. Replies from the model go out via {@link #sendPlain} and are left
     * alone -- the model already answers in the language it was asked in.
     */
    private void send(long chatId, String text) {
        try {
            api.sendMessage(chatId, Messages.t(text), null);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}: {}", chatId, e.getMessage());
        }
    }

    private void sendPlain(long chatId, String text) {
        try {
            api.sendPlainMessage(chatId, text);
        } catch (TelegramApiException e) {
            log.error("Failed to send agent reply to chat {}: {}", chatId, e.getMessage());
        }
    }

    private boolean sleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record OpenMenu(int messageId, MenuScreen screen) {
    }
}
