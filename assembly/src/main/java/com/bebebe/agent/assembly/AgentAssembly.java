package com.bebebe.agent.assembly;

import com.bebebe.agent.clipboard.ClipboardBridge;
import com.bebebe.agent.clipboard.LocalClipboardTool;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentCore;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.RequestBudget;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.memory.MemoryConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.PersonaStore;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.llm.LlmProviders;
import com.bebebe.agent.llm.SwitchableProvider;
import com.bebebe.agent.scheduler.JobStore;
import com.bebebe.agent.scheduler.SchedulerConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.stt.SttBridge;
import com.bebebe.agent.telegram.TelegramBridge;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.clipboard.ReadClipboardTool;
import com.bebebe.agent.tts.TtsBridge;
import com.bebebe.agent.transport.actions.ClipboardTool;
import com.bebebe.agent.watchdog.BuildInfo;
import com.bebebe.agent.watchdog.UpdateChecker;
import com.bebebe.agent.watchdog.Watchdog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Function;

public final class AgentAssembly implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentAssembly.class);

    public record Options(String ollamaBaseUrl, String telegramBaseUrl, Clock clock,
                          Function<String, Optional<Path>> speaker, boolean background,
                          ActionExecutor executor, ClipboardTool clipboard, boolean localVoice) {

        public Options(String ollamaBaseUrl, String telegramBaseUrl, Clock clock,
                       Function<String, Optional<Path>> speaker, boolean background) {
            this(ollamaBaseUrl, telegramBaseUrl, clock, speaker, background, null, null, true);
        }

        public static Options production() {
            return new Options(null, null, Clock.systemDefaultZone(), null, true);
        }

        public static Options server(ActionExecutor executor, ClipboardTool clipboard) {
            return new Options(null, null, Clock.systemDefaultZone(), null, true, executor, clipboard, false);
        }

        public boolean remote() {
            return executor != null;
        }
    }

    private final AppConfig config;
    private final AgentSwitch agentSwitch;
    private final LlmProviders providers;
    private final SwitchableProvider llm;
    private final ScriptLibrary library;
    private final MemoryStore memory;
    private final JobStore jobs;
    private final AgentCore core;
    private final NotesStore notes;
    private final PersonaStore personas;
    private final TelegramBridge telegram;
    private final Watchdog watchdog;
    private final UpdateChecker updates;
    private final BackupService backup;
    private final TtsBridge tts;
    private final SttBridge stt;
    private final com.bebebe.agent.stt.RemoteVoiceIngest telegramVoice;
    private final BuildInfo build;
    private final Path dataDir;
    private final Clock clock;

    private AgentAssembly(AppConfig config, AppSettings settings, Options options) {
        this.config = config;
        agentSwitch = new AgentSwitch(config.section("agent").bool("enabled_on_start", false));

        if (options.ollamaBaseUrl() != null) {

            settings.setEndpoint(options.ollamaBaseUrl());
        }
        // One clock for the whole graph, and its zone is the user's, not the machine's. Everything
        // that shows or computes a time for the user reads the zone from here.
        clock = com.bebebe.agent.config.UserClock.following(options.clock(), settings);
        if (!settings.timezoneChosen()) {
            log.warn("Time zone is not set ([agent] timezone): falling back to the machine's {}. "
                    + "On a server in another zone reminders will fire at the wrong local time -- "
                    + "set it in the window or in ⚙️ Settings in the chat.", clock.getZone());
        } else {
            log.info("Time zone: {}", clock.getZone());
        }
        providers = new LlmProviders(config, settings);
        llm = providers.switchable();
        log.info("Model provider: {} ({}, {})", llm.displayName(), llm.model(), llm.endpoint());

        ActionExecutor scripts = options.executor() != null
                ? options.executor()
                : new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(config.section(ScriptConfig.SECTION))));
        log.info("Script executor: {}", scripts.name());
        library = new ScriptLibrary(LibraryConfig.from(config.section(LibraryConfig.SECTION)));
        MemoryConfig memoryConfig = MemoryConfig.from(config.section(MemoryConfig.SECTION));
        memory = new MemoryStore(memoryConfig);
        jobs = new JobStore(SchedulerConfig.from(config.section(SchedulerConfig.SECTION)));
        ToolRegistry tools = Wiring.buildTools(config, clock);
        core = new AgentCore(agentSwitch, llm, scripts, library, memory, tools, jobs, clock,
                config.section("agent").integer("request_budget", RequestBudget.DEFAULT_LIMIT));
        core.setStopGrace(config.section("agent").seconds("stop_grace_seconds", Duration.ofSeconds(20)));

        // Recall by meaning, if there is a model to do it with. Always through Ollama: Anthropic
        // has no embedding API, so this is configured independently of the chat provider.
        core.setEmbeddings(providers.embeddings(
                config.section(MemoryConfig.SECTION).string("embedding_model", "")));
        core.setLiveReplies(settings::liveReplies);
        core.setScriptsEnabled(settings::scriptsEnabled);
        notes = Wiring.startNotes(config, tools, core, clock);
        dataDir = memoryConfig.dbPath().toAbsolutePath().getParent();
        personas = new PersonaStore(dataDir.resolve("personas.db"));
        core.setPersonas(personas);
        ClipboardTool clipboard = options.clipboard() != null
                ? options.clipboard()
                : new LocalClipboardTool(new ClipboardBridge());
        tools.register(new ReadClipboardTool(clipboard));

        telegram = Wiring.startTelegram(config, settings, agentSwitch, core, llm, library, memory,
                options.telegramBaseUrl());
        if (telegram != null) {
            telegram.attachJobs(jobs, clock::getZone);
            if (notes != null) {
                telegram.attachNotes(notes);
            }
            telegram.attachPersonas(personas);
        }
        Wiring.wireNotifier(core, telegram);
        watchdog = options.background() ? Wiring.startWatchdog(config, core, telegram) : null;

        for (String id : AppSettings.PROVIDERS) {
            providers.statsOf(id).persistTo(dataDir.resolve("llm-stats-" + id + ".json"));
        }
        build = BuildInfo.load();
        log.info("Build version: {}", build.describe());
        updates = options.background() ? Wiring.startUpdateChecker(config, build, dataDir, telegram) : null;
        backup = new BackupService(memoryConfig.dbPath(), dataDir.resolve("personas.db"), jobs.config().dbPath(),
                notes == null ? null : notes.dir(), config.path());
        if (telegram != null) {
            telegram.attachBackup(() -> {
                BackupService.Result r = backup.create(dataDir.resolve("backups"));
                return new TelegramBridge.BackupOutcome(r.archive(), r.summary());
            });
        }

        if (options.speaker() != null) {
            tts = null;
            if (telegram != null) {
                telegram.attachTts(options.speaker(), config.section("tts").bool("reply_to_text", false));
            }
        } else {
            tts = Wiring.startTts(config);
            if (telegram != null && tts != null && tts.isReady()) {
                telegram.attachTts(tts::synthesize, tts.config().replyToText());
            }
        }

        stt = options.background() && options.localVoice() ? Wiring.startStt(config, agentSwitch, this::handleVoice) : null;
        telegramVoice = options.background() && telegram != null
                ? Wiring.startTelegramVoice(config, agentSwitch, this::handleVoice, telegram)
                : null;
        Wiring.wireSettings(settings, llm, telegram, config);
    }

    public static AgentAssembly build(AppConfig config, AppSettings settings, Options options) {
        return new AgentAssembly(config, settings, options);
    }

    public void handleVoice(UserMessage message) {
        AgentReply reply = core.handle(message);
        Wiring.routeVoiceReply(telegram, message, reply);
    }

    public BuildInfo build() {
        return build;
    }

    public Optional<UpdateChecker> updates() {
        return Optional.ofNullable(updates);
    }

    public BackupService backup() {
        return backup;
    }

    public Path dataDir() {
        return dataDir;
    }

    public AppConfig config() {
        return config;
    }

    public AgentSwitch agentSwitch() {
        return agentSwitch;
    }

    public SwitchableProvider llm() {
        return llm;
    }

    public AgentCore core() {
        return core;
    }

    public TelegramBridge telegram() {
        return telegram;
    }

    public JobStore jobs() {
        return jobs;
    }

    public MemoryStore memory() {
        return memory;
    }

    public NotesStore notes() {
        return notes;
    }

    public PersonaStore personas() {
        return personas;
    }

    public ScriptLibrary library() {
        return library;
    }

    public Optional<Watchdog> watchdog() {
        return Optional.ofNullable(watchdog);
    }

    /**
     * Shutting the whole process down, from wherever: the JVM shutdown hook of either entry
     * point, a SIGTERM from systemd, the window being closed.
     *
     * <p>It turns the agent off first, and that is the point. Consolidating memory hangs off
     * {@code onBeforeStop}, and before this that hook ran <b>only</b> when somebody flipped the
     * toggle by hand. A restart, a reboot or a {@code systemctl restart} closed the stores and
     * exited, leaving the tail of the conversation unconsolidated and the session never marked
     * as ended -- which is exactly what the live database showed: messages, no facts, no
     * {@code ended_at}.
     */
    @Override
    public void close() {
        try {
            if (agentSwitch.isOn()) {
                log.info("Shutting down: switching the agent off so the lifecycle hooks run");
                agentSwitch.turnOff();
            }
        } catch (RuntimeException e) {

            log.error("Lifecycle hooks failed while shutting down -- closing anyway", e);
        }
        for (AutoCloseable c : new AutoCloseable[] {watchdog, updates, stt, telegramVoice, telegram, tts, library, memory, jobs,
                notes, personas, llm}) {
            if (c == null) {
                continue;
            }
            try {
                c.close();
            } catch (Exception e) {
                log.warn("Cannot close {}: {}", c.getClass().getSimpleName(), e.toString());
            }
        }
    }
}
