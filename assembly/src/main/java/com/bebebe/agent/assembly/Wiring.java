package com.bebebe.agent.assembly;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.config.ConfigException;
import com.bebebe.agent.core.AgentCore;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.logging.AppLogging;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.notes.NotesConfig;
import com.bebebe.agent.tts.TtsBridge;
import com.bebebe.agent.watchdog.BuildInfo;
import com.bebebe.agent.watchdog.UpdateChecker;
import com.bebebe.agent.watchdog.Watchdog;
import com.bebebe.agent.watchdog.WatchdogConfig;
import com.bebebe.agent.tts.TtsConfig;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.tools.notes.NotesTool;
import com.bebebe.agent.script.library.ScriptEntry;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.menu.MenuController;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.time.GetCurrentTimeTool;
import com.bebebe.agent.tools.web.WebSearchConfig;
import com.bebebe.agent.tools.web.WebSearchTool;
import com.bebebe.agent.stt.SttBridge;
import com.bebebe.agent.stt.SttConfig;
import com.bebebe.agent.telegram.TelegramBridge;
import com.bebebe.agent.telegram.TelegramConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public final class Wiring {

    private static final Logger log = LoggerFactory.getLogger(Wiring.class);

    private Wiring() {
    }

    public static ToolRegistry buildTools(AppConfig config) {
        ToolRegistry registry = new ToolRegistry().register(new GetCurrentTimeTool());
        try {
            registry.register(new WebSearchTool(WebSearchConfig.from(config.section(WebSearchConfig.SECTION))));
        } catch (RuntimeException e) {
            log.error("web_search not enabled: {}", e.getMessage());
        }
        return registry;
    }

    public static Watchdog startWatchdog(AppConfig config, AgentCore core, TelegramBridge telegram) {
        try {
            Watchdog watchdog = new Watchdog(WatchdogConfig.from(config.section(WatchdogConfig.SECTION)),
                    core.activity(), core.worker(), (conversation, text) -> {
                        if (telegram == null) {
                            log.warn("Watchdog notification (nowhere to deliver): {}", text);
                            return;
                        }
                        java.util.Optional<Long> chatId = conversation.filter(c -> c.startsWith("TELEGRAM:"))
                                .flatMap(Wiring::parseChat).or(telegram::lastChatId);
                        chatId.ifPresentOrElse(id -> telegram.sendTo(id, text),
                                () -> log.warn("Watchdog notification (nowhere to deliver): {}", text));
                    });
            watchdog.start();
            return watchdog;
        } catch (RuntimeException e) {
            log.error("Section [watchdog] is invalid: {}", e.getMessage());
            return null;
        }
    }

    public static UpdateChecker startUpdateChecker(AppConfig config, BuildInfo build, java.nio.file.Path dataDir,
                                                    TelegramBridge telegram) {
        UpdateChecker checker = new UpdateChecker(UpdateChecker.Config.from(config.section(UpdateChecker.Config.SECTION)),
                build, dataDir.resolve("update-check.json"));
        checker.addListener(status -> {
            if (!checker.shouldNotify(status)) {
                return;
            }
            String text = "🆕 " + checker.repoSlug() + " (" + checker.branch() + ") has new commits: "
                    + status.describe() + ". Current build: " + build.shortCommit() + ". Update manually.";
            log.info(text);
            if (telegram != null) {
                telegram.lastChatId().ifPresent(id -> telegram.sendTo(id, text));
            }
        });
        checker.start();
        return checker;
    }

    public static TtsBridge startTts(AppConfig config) {
        try {
            return new TtsBridge(TtsConfig.from(config.section(TtsConfig.SECTION)));
        } catch (RuntimeException e) {
            log.error("Section [tts] is invalid: {}", e.getMessage());
            return null;
        }
    }

    public static NotesStore startNotes(AppConfig config, ToolRegistry tools, AgentCore core) {
        try {
            NotesStore notes = new NotesStore(NotesConfig.from(config.section(NotesConfig.SECTION)));
            NotesTool.ReminderLink link = core.reminders() == null ? null : new NotesTool.ReminderLink() {
                @Override
                public long schedule(java.time.Instant fireAt, String prompt, String summary) {
                    return core.reminders().schedule(fireAt, prompt, summary, "");
                }

                @Override
                public boolean cancel(long jobId) {
                    return core.reminders().store().cancel(jobId);
                }
            };
            tools.register(new NotesTool(notes, link, java.time.Clock.systemDefaultZone()));
            return notes;
        } catch (RuntimeException e) {
            log.error("Notes not enabled: {}", e.getMessage());
            return null;
        }
    }

    public static void applyLogging(AppConfig config) {
        var section = config.section("logging");
        java.nio.file.Path dir = expandHome(section.string("dir", "logs"));
        String level = section.string("level", "INFO");

        java.util.Map<String, String> overrides = new java.util.LinkedHashMap<>();
        config.optionalSection("logging.levels").ifPresent(levels ->
                levels.keys().forEach(key -> levels.string(key).ifPresent(v -> overrides.put(key, v))));

        AppLogging.applyConfig(dir, level, overrides);
    }

    public static java.nio.file.Path expandHome(String raw) {
        if (raw.startsWith("~/")) {
            return java.nio.file.Path.of(System.getProperty("user.home"), raw.substring(2));
        }
        return java.nio.file.Path.of(raw);
    }

    public static AppConfig readConfig() {
        try {
            return AppConfig.load();
        } catch (ConfigException e) {
            java.nio.file.Path created = bootstrapConfigFromExample();
            if (created != null) {
                try {
                    return AppConfig.load(created);
                } catch (ConfigException again) {
                    log.warn("The created config could not be read ({}), continuing with defaults", again.getMessage());
                }
            } else {
                log.warn("Config could not be read ({}), continuing with defaults", e.getMessage());
            }
            return AppConfig.fromToml("");
        }
    }

    public static final java.nio.file.Path CONFIG_EXAMPLE = java.nio.file.Path.of("config", "agent.example.toml");
    public static final java.nio.file.Path CONFIG_DEV = java.nio.file.Path.of("config", "agent.toml");

    public static java.nio.file.Path bootstrapConfigFromExample() {
        boolean anyExists = com.bebebe.agent.config.ConfigPaths.candidates().stream()
                .anyMatch(java.nio.file.Files::exists);
        if (anyExists || !java.nio.file.Files.isReadable(CONFIG_EXAMPLE)) {
            return null;
        }
        try {
            java.nio.file.Files.createDirectories(CONFIG_DEV.toAbsolutePath().getParent());
            java.nio.file.Files.copy(CONFIG_EXAMPLE, CONFIG_DEV);
            try {
                java.nio.file.Files.setPosixFilePermissions(CONFIG_DEV,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {

            }
            log.warn("No config existed -- created {} from the example. Fill in the keys in the Settings window "
                    + "or in the file; secrets are stored there in plain text, permissions set to 600.",
                    CONFIG_DEV.toAbsolutePath());
            return CONFIG_DEV;
        } catch (java.io.IOException io) {
            log.warn("Cannot create {} from the example: {}", CONFIG_DEV, io.getMessage());
            return null;
        }
    }

    public static TelegramBridge startTelegram(AppConfig config,
                                        AppSettings settings,
                                        AgentSwitch agentSwitch,
                                        AgentCore core,
                                        com.bebebe.agent.llm.LlmProvider llm,
                                        ScriptLibrary library,
                                        MemoryStore memory,
                                        String apiBaseUrl) {
        TelegramConfig telegramConfig;
        try {
            telegramConfig = TelegramConfig.from(config.section(TelegramConfig.SECTION));
        } catch (RuntimeException e) {
            log.error("Section [telegram] is invalid: {}", e.getMessage());
            return null;
        }

        java.util.concurrent.atomic.AtomicReference<TelegramBridge> self =
                new java.util.concurrent.atomic.AtomicReference<>();

        MenuController.ConfirmationActions actions = new MenuController.ConfirmationActions() {
            @Override
            public AgentReply confirm(String token) {

                var pending = core.confirmations().peek(token).orElse(null);
                ScriptEntry script = pending == null ? null : pending.script();
                AgentReply reply = core.confirm(token);
                TelegramBridge bridge = self.get();

                if (pending != null && pending.message().source() == com.bebebe.agent.core.MessageSource.VOICE
                        && reply instanceof AgentReply.Text && bridge != null) {
                    AgentReply spokenReply = reply;
                    bridge.lastChatId().ifPresent(chatId -> bridge.deliver(chatId, spokenReply, true));
                    reply = AgentReply.text("✅ Done -- reply below.");
                }
                if (script != null && script.requiresConfirmation() && bridge != null) {
                    bridge.lastChatId().ifPresent(chatId -> bridge.offerTrust(chatId, script));
                }
                return reply;
            }

            @Override
            public AgentReply cancel(String token) {
                return core.cancel(token);
            }

            @Override
            public AgentReply trust(long scriptId) {
                return core.trustScript(scriptId);
            }
        };

        TelegramBridge bridge = new TelegramBridge(
                telegramConfig,
                agentSwitch,
                core::handle,
                settings,

                llm::listModels,
                library,
                actions,
                memory,
                core::resolveEntity,
                apiBaseUrl == null ? new com.bebebe.agent.telegram.api.TelegramApi(telegramConfig.botToken())
                        : new com.bebebe.agent.telegram.api.TelegramApi(telegramConfig.botToken(), apiBaseUrl));
        self.set(bridge);
        bridge.start();
        return bridge;
    }

    public static SttBridge startStt(AppConfig config,
                              AgentSwitch agentSwitch,
                              java.util.function.Consumer<UserMessage> voiceHandler) {
        SttConfig sttConfig;
        try {
            sttConfig = SttConfig.from(config.section(SttConfig.SECTION));
        } catch (RuntimeException e) {
            log.error("Section [stt] is invalid: {}", e.getMessage());
            return null;
        }

        SttBridge bridge = new SttBridge(sttConfig, agentSwitch, voiceHandler::accept);
        bridge.start();
        return bridge;
    }

    public static void wireNotifier(AgentCore core, TelegramBridge telegram) {
        core.setNotifier(outbound -> {
            if (telegram == null) {
                log.info("Nowhere to deliver the memory message: {}", outbound.reply().asPlainText());
                return;
            }
            java.util.Optional<Long> chatId = outbound.conversationKey().startsWith("TELEGRAM:")
                    ? parseChat(outbound.conversationKey())
                    : telegram.lastChatId();
            chatId.ifPresentOrElse(
                    id -> telegram.deliver(id, outbound.reply()),
                    () -> log.info("Nowhere to deliver the memory message: {}", outbound.reply().asPlainText()));
        });
    }

    public static java.util.Optional<Long> parseChat(String conversationKey) {
        try {
            return java.util.Optional.of(Long.parseLong(conversationKey.substring("TELEGRAM:".length())));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    public static void routeVoiceReply(TelegramBridge telegram, UserMessage message, AgentReply reply) {
        if (reply instanceof AgentReply.Silence) {
            return;
        }

        if (telegram == null || telegram.lastChatId().isEmpty()) {
            log.info("Reply to a voice request (nowhere to send): {}", reply.asPlainText());
            return;
        }
        long chatId = telegram.lastChatId().get();
        if (reply instanceof AgentReply.Text text) {
            telegram.sendTo(chatId, "🎤 " + message.text());
            telegram.deliver(chatId, text, true);
        } else {

            telegram.sendTo(chatId, "🎤 " + message.text());
            telegram.deliver(chatId, reply);
        }
        log.info("Reply to a voice request sent to chat {}", chatId);
    }

    public static void wireSettings(AppSettings settings,
                             com.bebebe.agent.llm.SwitchableProvider llm,
                             TelegramBridge telegram,
                             AppConfig config) {
        settings.addListener(field -> {
            switch (field) {

                case LLM_PROVIDER, LLM_ENDPOINT -> llm.switchTo(settings.provider());
                case LLM_MODEL -> {
                    if (llm.id().equals(settings.provider())) {
                        llm.setModel(settings.model());
                    }
                }
                case LLM_API_KEY -> {
                    if (llm.id().equals(settings.provider())) {
                        llm.setApiKey(settings.apiKey());
                    }
                }
                case TELEGRAM_BOT_TOKEN -> reconnectTelegram(settings, telegram, config);

                case ALLOWED_USERNAMES, PROACTIVE_HINTS, VOICE_REPLIES, LIVE_REPLIES, TYPING_INDICATOR, SCRIPTS_ENABLED -> { }
            }
        });
    }

    public static void reconnectTelegram(AppSettings settings, TelegramBridge telegram, AppConfig config) {
        if (telegram == null) {
            log.warn("Token changed, but the Telegram bridge was not created -- a process restart is needed");
            return;
        }
        try {
            TelegramConfig base = TelegramConfig.from(config.section(TelegramConfig.SECTION));
            telegram.reconnect(new TelegramConfig(
                    base.enabled(),
                    settings.telegramBotToken(),
                    Set.copyOf(settings.allowedUsernames()),
                    base.allowedChatIds(),
                    base.pollTimeout()));
        } catch (RuntimeException e) {
            log.error("Failed to reconnect Telegram with the new token: {}", e.getMessage());
        }
    }
}
