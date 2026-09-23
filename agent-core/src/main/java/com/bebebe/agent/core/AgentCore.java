package com.bebebe.agent.core;

import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.memory.DialogMessage;
import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.MessageRole;
import com.bebebe.agent.llm.LlmException;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmRequest;
import com.bebebe.agent.llm.LlmRequest.LlmMessage;
import com.bebebe.agent.llm.LlmResponse;
import com.bebebe.agent.llm.OllamaProvider;
import com.bebebe.agent.llm.SwitchableProvider;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.script.library.ScriptEntry;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.scheduler.JobStore;
import com.bebebe.agent.scheduler.SchedulerConfig;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.transport.actions.ActionResult;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.ToolResult;
import com.bebebe.agent.tools.web.FreshnessHints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.time.Duration;
import java.util.function.Supplier;

public final class AgentCore {

    private static final Logger log = LoggerFactory.getLogger(AgentCore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentSwitch agentSwitch;

    private final LlmProvider llm;

    private volatile LlmProvider pinned;

    private final ActionExecutor scripts;
    private final ScriptLibrary library;
    private final MemoryStore memory;
    private final MemoryConsolidator consolidator;
    private final MemoryGardener gardener;
    private final MemoryRecall recall;
    private final ToolRegistry tools;
    private final ReminderService reminders;
    private final PeriodicConsolidation sweeper = new PeriodicConsolidation();

    /** Kept for the "Now:" block of the prompt: its zone is the user's, not the machine's. */
    private final java.time.Clock clock;
    private final ConfirmationStore confirmations = new ConfirmationStore();
    private final int budgetLimit;

    private volatile Consumer<Outbound> notifier = outbound -> log.info(
            "Nowhere to deliver: {} -> {}", outbound.conversationKey(), outbound.reply().asPlainText());

    public record Outbound(String conversationKey, AgentReply reply) {
    }

    private final ConcurrentHashMap<String, Long> lastExecuted = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> lastOutput = new ConcurrentHashMap<>();

    public AgentCore(AgentSwitch agentSwitch,
                     OllamaClient ollama,
                     ActionExecutor scripts,
                     ScriptLibrary library,
                     MemoryStore memory) {
        this(agentSwitch, new OllamaProvider(ollama), scripts, library, memory, new ToolRegistry(), RequestBudget.DEFAULT_LIMIT);
    }

    public AgentCore(AgentSwitch agentSwitch,
                     OllamaClient ollama,
                     ActionExecutor scripts,
                     ScriptLibrary library,
                     MemoryStore memory,
                     int budgetLimit) {
        this(agentSwitch, new OllamaProvider(ollama), scripts, library, memory, new ToolRegistry(), budgetLimit);
    }

    public AgentCore(AgentSwitch agentSwitch,
                     LlmProvider llm,
                     ActionExecutor scripts,
                     ScriptLibrary library,
                     MemoryStore memory,
                     ToolRegistry tools,
                     int budgetLimit) {
        this(agentSwitch, llm, scripts, library, memory, tools, null, java.time.Clock.systemDefaultZone(), budgetLimit);
    }

    public AgentCore(AgentSwitch agentSwitch,
                     LlmProvider llm,
                     ActionExecutor scripts,
                     ScriptLibrary library,
                     MemoryStore memory,
                     ToolRegistry tools,
                     JobStore jobs,
                     java.time.Clock clock,
                     int budgetLimit) {
        this.agentSwitch = agentSwitch;
        this.clock = clock;
        this.llm = llm;
        this.scripts = scripts;
        this.library = library;
        this.memory = memory;
        this.tools = tools;
        this.consolidator = new MemoryConsolidator(memory, () -> llm);
        this.gardener = new MemoryGardener(memory, () -> llm);
        this.recall = new MemoryRecall(memory);

        // Recall stops being a single guess made before the model has said anything: when the
        // keyword pre-load misses, the model can ask for what it half-remembers, write something
        // down the moment it is asked to, and retract what turned out to be wrong.
        CoreMemoryAccess access = new CoreMemoryAccess(memory, recall, clock);
        tools.register(new com.bebebe.agent.tools.memory.RecallTool(access));
        tools.register(new com.bebebe.agent.tools.memory.RememberTool(access));
        tools.register(new com.bebebe.agent.tools.memory.ForgetTool(access));

        // A conversation that ends has to leave something behind, and closing it is the only
        // moment when that is possible: the store itself knows nothing about the model.
        memory.setSessionClosedListener(closed -> sweeper.submit(() -> closeOut(closed)));
        this.budgetLimit = budgetLimit;
        this.activity = new ActivityMonitor();
        this.worker = new AgentWorker(activity);

        agentSwitch.lifecycle().register(new GracefulStopHook());
        agentSwitch.lifecycle().register(new MemoryConsolidationHook());
        if (jobs != null) {
            SchedulerConfig schedulerConfig = jobs.config();
            this.reminders = new ReminderService(schedulerConfig, jobs, clock,
                    this::handleSystem, out -> notifier.accept(out));

            tools.register(new com.bebebe.agent.tools.reminder.SetReminderTool(reminders::schedule, clock));
            tools.register(new com.bebebe.agent.tools.reminder.ManageRemindersTool(
                    new com.bebebe.agent.tools.reminder.ManageRemindersTool.Link() {
                        @Override
                        public boolean cancel(long jobId) {
                            return jobs.cancel(jobId);
                        }

                        @Override
                        public boolean reschedule(long jobId, java.time.Instant fireAt) {
                            return jobs.reschedule(jobId, fireAt);
                        }
                    }, clock));
            agentSwitch.lifecycle().register(reminders);
            if (agentSwitch.isOn()) {
                reminders.onAfterStart();
            }
        } else {
            this.reminders = null;
        }
        log.debug("Lifecycle hooks registered: {}", agentSwitch.lifecycle().size());
    }

    public ReminderService reminders() {
        return reminders;
    }

    private final ActivityMonitor activity;

    private final AgentWorker worker;

    private volatile Duration stopGrace = Duration.ofSeconds(20);

    public ActivityMonitor activity() {
        return activity;
    }

    public AgentWorker worker() {
        return worker;
    }

    public void setStopGrace(Duration grace) {
        this.stopGrace = grace;
    }

    private final class GracefulStopHook implements AgentLifecycleHook {
        @Override
        public String name() {
            return "graceful-stop";
        }

        @Override
        public void onBeforeStop() {
            if (!worker.inFlight()) {
                return;
            }
            String what = activity.inFlight().map(f -> f.what()).orElse("?");
            log.atInfo().addKeyValue("event", "stop.waiting").addKeyValue("what", what)
                    .log("[hook graceful-stop] waiting for '{}' to finish, up to {} s", what, stopGrace.toSeconds());
            if (worker.awaitIdle(stopGrace)) {
                log.info("[hook graceful-stop] processing finished, safe to stop");
                return;
            }
            log.atWarn().addKeyValue("event", "stop.interrupt").addKeyValue("what", what)
                    .log("[hook graceful-stop] not finished within {} s -- interrupting", stopGrace.toSeconds());
            worker.interruptCurrent();
            worker.awaitIdle(Duration.ofSeconds(10));
        }
    }

    private void handleSystem(UserMessage message) {
        AgentReply reply = handle(message);
        reminders.deliver(message, reply);
    }

    public void setNotifier(Consumer<Outbound> notifier) {
        this.notifier = notifier;
    }

    private volatile java.util.function.BooleanSupplier liveReplies = () -> false;

    private volatile java.util.function.BooleanSupplier scriptsEnabled = () -> true;

    public void setScriptsEnabled(java.util.function.BooleanSupplier scriptsEnabled) {
        this.scriptsEnabled = scriptsEnabled;
    }

    private boolean scriptsAllowed() {
        return scriptsEnabled.getAsBoolean();
    }

    public void setLiveReplies(java.util.function.BooleanSupplier liveReplies) {
        this.liveReplies = liveReplies;
    }

    private boolean live() {
        return liveReplies.getAsBoolean();
    }

    private AgentReply replyFrom(AgentDecision decision) {
        return live() && decision.replies().size() > 1
                ? AgentReply.parts(decision.replies())
                : AgentReply.text(decision.reply());
    }

    private AgentReply replyFromText(String text) {
        if (!live() || !text.contains(DecisionProtocol.LIVE_SPLIT_MARKER)) {
            return AgentReply.text(text);
        }
        List<String> parts = java.util.Arrays.stream(text.split("(?m)^\\s*---\\s*$"))
                .map(String::strip).filter(p -> !p.isEmpty()).toList();
        return parts.size() > 1 ? AgentReply.parts(parts) : AgentReply.text(text.replace(DecisionProtocol.LIVE_SPLIT_MARKER, "").strip());
    }

    private volatile com.bebebe.agent.memory.PersonaStore personas;

    public void setPersonas(com.bebebe.agent.memory.PersonaStore personas) {
        this.personas = personas;
    }

    private volatile SemanticRecall semantic;

    /**
     * Turns on recall by meaning. Without it everything stays lexical, which is what it has
     * always been -- an embedding model is an option, not a requirement.
     */
    public void setEmbeddings(com.bebebe.agent.llm.EmbeddingProvider embeddings) {
        if (embeddings == null || !embeddings.isReady()) {
            log.info("Embeddings are not configured: recall stays lexical");
            return;
        }
        SemanticRecall search = new SemanticRecall(memory, embeddings);
        this.semantic = search;
        recall.useSemantic(search);
        log.info("Recall by meaning is on, model «{}»", embeddings.model());
        sweeper.submit(this::embedPending);
    }

    /**
     * Embeds what has been remembered but not yet vectorised, a batch at a time.
     *
     * <p>On the background thread and in batches because the first run after switching this on
     * faces everything ever remembered, and a vector is needed by the next question rather than
     * by the one being answered.
     */
    private void embedPending() {
        SemanticRecall search = semantic;
        if (search == null || !agentSwitch.isOn()) {
            return;
        }
        try {
            if (search.backfill() > 0) {

                // More waiting: come back for the next batch instead of holding the thread.
                sweeper.submit(this::embedPending);
            }
        } catch (RuntimeException e) {
            log.error("Embedding of remembered facts failed", e);
        }
    }

    private String personaBlock() {
        com.bebebe.agent.memory.PersonaStore store = personas;
        return store == null ? "" : store.promptBlock();
    }

    private String replySystemPrompt() {
        return "You are a personal AI agent. Answer briefly, in Russian."
                + DecisionProtocol.confidentialityBlock()
                + (live() ? DecisionProtocol.liveFreeTextHint() : "") + personaBlock();
    }

    public AgentSwitch agentSwitch() {
        return agentSwitch;
    }

    public LlmProvider llm() {
        return llm;
    }

    private LlmProvider provider() {
        LlmProvider p = pinned;
        if (p != null) {
            return p;
        }
        return llm instanceof SwitchableProvider sw ? sw.pin() : llm;
    }

    public AgentCore(AgentSwitch agentSwitch, OllamaClient ollama, ActionExecutor scripts, ScriptLibrary library,
                     MemoryStore memory, ToolRegistry tools, JobStore jobs, java.time.Clock clock, int budgetLimit) {
        this(agentSwitch, new OllamaProvider(ollama), scripts, library, memory, tools, jobs, clock, budgetLimit);
    }

    public ActionExecutor executor() {
        return scripts;
    }

    public ScriptLibrary library() {
        return library;
    }

    public ConfirmationStore confirmations() {
        return confirmations;
    }

    public MemoryStore memory() {
        return memory;
    }

    public MemoryConsolidator consolidator() {
        return consolidator;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public AgentReply resolveEntity(String token, boolean sameAsCandidate) {
        return consolidator.resolve(token, sameAsCandidate);
    }

    public AgentReply handle(UserMessage message) {
        if (!agentSwitch.isOn()) {
            log.debug("Message ignored: agent is off -- {}", message);
            return AgentReply.silence();
        }
        if (message.isEmpty()) {
            return AgentReply.silence();
        }
        if (message.hasImages() && !provider().supportsImages()) {

            // Saying so is the whole point: dropping the attachment silently and answering the
            // caption as if there were no picture is how a user ends up trusting a wrong answer.
            log.atWarn()
                    .addKeyValue("event", "image.unsupported")
                    .addKeyValue("provider", provider().displayName())
                    .addKeyValue("model", provider().model())
                    .log("An image arrived, but model «{}» cannot look at it", provider().model());
            return AgentReply.text(DecisionProtocol.imagesUnsupportedMessage(
                    provider().displayName(), provider().model()));
        }

        try {
            return worker.run(message.text(), replyConversationKey(message), () -> handleInWorker(message));
        } catch (AgentWorker.Restarted e) {
            log.warn("Processing of '{}' interrupted: {}", message.text(), e.getMessage());
            return AgentReply.silence();
        }
    }

    private AgentReply handleInWorker(UserMessage message) {
        pinned = llm instanceof SwitchableProvider sw ? sw.pin() : llm;
        try {
            return handlePinned(message);
        } finally {
            pinned = null;
        }
    }

    private AgentReply handlePinned(UserMessage message) {

        try (TraceContext.Scope ignored = TraceContext.open(message.traceId())) {
            log.atInfo()
                    .addKeyValue("event", "request.start")
                    .addKeyValue("source", message.source().name())
                    .addKeyValue("reply_to", message.replyTarget().orElse(""))
                    .addKeyValue("text", message.text())
                    .log("Handling message {}", message);
            RequestBudget budget = new RequestBudget(budgetLimit);
            DialogSession session = memory.openOrContinue(conversationKey(message));
            memory.append(session.id(), MessageRole.USER, message.text(),
                    message.source().name(), message.traceId());

            if (reminders != null) {

                reminders.bindConversation(replyConversationKey(message));
            }
            try {
                TaskAttempts attempts = new TaskAttempts();
                AgentReply reply = guarded(budget,
                        () -> loop(message, decide(message, session, budget), budget, 0, null, attempts));
                afterReply(message, session, reply);
                rememberOutcome(message, attempts);
                return reply;
            } finally {
                if (reminders != null) {
                    reminders.unbindConversation();
                }
            }
        }
    }

    private static String replyConversationKey(UserMessage message) {
        if (message.source() == MessageSource.SYSTEM) {
            return message.replyTarget().map(id -> "TELEGRAM:" + id).orElse("");
        }
        return conversationKey(message);
    }

    private void afterReply(UserMessage message, DialogSession session, AgentReply reply) {
        if (reply instanceof AgentReply.Text text && !text.text().isBlank()) {
            memory.append(session.id(), MessageRole.ASSISTANT, text.text(),
                    message.source().name(), message.traceId());
        }
        DialogSession fresh = memory.session(session.id()).orElse(session);
        int pending = memory.unconsolidated(fresh).size();
        if (pending >= memory.config().consolidateEvery()) {
            log.info("{} messages accumulated -- consolidating", pending);
            deliverQuestions(consolidator.consolidate(fresh), fresh.conversationKey());
        }
    }

    /**
     * A request that ran out of ways to succeed leaves a note behind.
     *
     * <p>{@link TaskAttempts} dies with the request, and with it used to die the knowledge that
     * the whole approach does not work on this machine. Asked the same thing next week, the agent
     * set off down the same dead end and paid for it again.
     *
     * <p>Written as an ordinary fact, so everything memory already does applies to it: it is
     * found by the words of the next similar request, it can be confirmed or retracted in the
     * review queue, and the near-duplicate guard turns a second identical failure into a
     * confirmation rather than a second line.
     */
    private void rememberOutcome(UserMessage message, TaskAttempts attempts) {
        String text = attempts.outcomeFor(message.text());
        if (text.isEmpty()) {
            return;
        }
        // Against the other dead ends, not against facts about the user: outcomes are kept out
        // of that list on purpose, and comparing with a list that cannot contain them would
        // quietly write the same note again every week.
        Optional<Fact> known = FactDedup.duplicateIn(memory.factsByCategory(FactCategory.OUTCOME), text);
        if (known.isPresent()) {
            memory.confirmFact(known.get().id());
            log.debug("The same dead end is already remembered as #{}", known.get().id());
            return;
        }
        Fact stored = memory.addFact(text, FactCategory.OUTCOME, java.time.LocalDate.now(clock), null,
                List.of(), com.bebebe.agent.memory.FactSource.EXTRACTED, List.of());
        sweeper.submit(this::embedPending);
        log.atInfo()
                .addKeyValue("event", "memory.outcome")
                .addKeyValue("fact_id", stored.id())
                .addKeyValue("attempts", attempts.size())
                .log("Dead end remembered: {}", text);
    }

    private void deliverQuestions(List<AgentReply.EntityQuestion> questions, String conversationKey) {
        for (AgentReply.EntityQuestion question : questions) {
            notifier.accept(new Outbound(conversationKey, question));
        }
    }

    public AgentReply confirm(String token) {
        Optional<PendingExecution> found = confirmations.take(token);
        if (found.isEmpty()) {
            return AgentReply.text("Запрос устарел — повторите, пожалуйста.");
        }
        PendingExecution pending = found.get();
        try {
            return worker.run(pending.message().text(), replyConversationKey(pending.message()),
                    () -> confirmInWorker(pending));
        } catch (AgentWorker.Restarted e) {
            log.warn("Confirmed launch interrupted: {}", e.getMessage());
            return AgentReply.silence();
        }
    }

    private AgentReply confirmInWorker(PendingExecution pending) {

        pinned = llm instanceof SwitchableProvider sw ? sw.pin() : llm;
        try {
            return confirmPinned(pending);
        } finally {
            pinned = null;
        }
    }

    private AgentReply confirmPinned(PendingExecution pending) {

        try (TraceContext.Scope ignored = TraceContext.open(pending.message().traceId())) {
            log.atInfo()
                    .addKeyValue("event", "confirmation.accepted")
                    .addKeyValue("script_id", pending.script().id())
                    .log("Launch of '{}' confirmed ({})", pending.script().displayName(), pending.budget());

            if (!scriptsAllowed()) {

                log.atWarn().addKeyValue("event", "script.disabled").addKeyValue("script_id", pending.script().id())
                        .log("Scripts are disabled -- confirmed launch of '{}' is not executed", pending.script().displayName());
                return AgentReply.text(DecisionProtocol.scriptsDisabledMessage());
            }

            AgentReply reply = guarded(pending.budget(), () -> {
                Step step = runOnce(pending.message(), pending.script(), pending.code(), pending.budget(),
                        pending.attempts());
                return continueFrom(pending.message(), step, pending.budget(), 1, pending.script(),
                        pending.attempts());
            });
            memory.activeSession(conversationKey(pending.message()))
                    .ifPresent(session -> afterReply(pending.message(), session, reply));
            rememberOutcome(pending.message(), pending.attempts());
            return reply;
        }
    }

    public AgentReply cancel(String token) {
        Optional<PendingExecution> pending = confirmations.peek(token);
        pending.ifPresent(execution -> {
            try (TraceContext.Scope ignored = TraceContext.open(execution.message().traceId())) {
                log.atInfo()
                        .addKeyValue("event", "confirmation.cancelled")
                        .addKeyValue("script_id", execution.script().id())
                        .log("Launch of '{}' cancelled by the user", execution.script().displayName());
            }
        });
        confirmations.cancel(token);
        return AgentReply.text(pending
                .map(execution -> "Отменено: «" + execution.script().displayName() + "» не запускался.")
                .orElse("Отменено."));
    }

    public AgentReply trustScript(long scriptId) {
        Optional<ScriptEntry> entry = library.byId(scriptId);
        if (entry.isEmpty()) {
            return AgentReply.text("Скрипт не найден.");
        }
        library.setRequiresConfirmation(scriptId, false);
        return AgentReply.text("Больше не буду спрашивать перед запуском «"
                + entry.get().displayName() + "». Вернуть можно в меню: ✅ Confirmations.");
    }

    private AgentReply guarded(RequestBudget budget, Supplier<AgentReply> body) {
        try {
            return body.get();
        } catch (RequestBudget.BudgetExhaustedException e) {
            log.warn("Budget exhausted after {} calls: {}", budget.used(), budget.spentOn());
            return AgentReply.text(DecisionProtocol.budgetExhaustedMessage(budget));
        } catch (LlmException e) {

            // The message carries an endpoint, an HTTP code and sometimes the provider's own
            // wording -- diagnostics for the log, not an answer for the user.
            log.error("Model unavailable: {}", e.getMessage(), e);
            return AgentReply.text(DecisionProtocol.modelUnavailableMessage());
        } finally {
            log.atInfo()
                    .addKeyValue("event", "request.end")
                    .addKeyValue("budget_used", budget.used())
                    .addKeyValue("budget_limit", budget.limit())
                    .log("Model calls spent: {}/{}", budget.used(), budget.limit());
        }
    }

    private sealed interface Step {

        record Done(AgentReply reply) implements Step {
        }

        record Retry(AgentDecision decision) implements Step {
        }
    }

    private AgentReply loop(UserMessage message, AgentDecision decision, RequestBudget budget, int attempt,
                            ScriptEntry failed, TaskAttempts attempts) {
        AgentDecision current = decision;
        int fixAttempt = attempt;
        ScriptEntry lastFailed = failed;

        while (true) {
            if (!current.isUsable()) {
                log.warn("Unusable model decision: {}", current);
                return AgentReply.text("Не разобрался, что нужно сделать. Попробуйте сформулировать иначе.");
            }

            if (current.type() == DecisionType.REPLY) {
                return replyFrom(current);
            }

            if (current.type() == DecisionType.TOOL_CALL) {
                return runTool(message, current, budget);
            }

            if (!scriptsAllowed()) {
                log.atWarn().addKeyValue("event", "script.disabled").addKeyValue("type", current.type().name())
                        .log("Scripts are disabled -- launch skipped");
                return AgentReply.text(DecisionProtocol.scriptsDisabledMessage());
            }

            Optional<ScriptEntry> resolved;
            if (lastFailed != null && !current.pythonCode().isBlank()
                    && (current.type() == DecisionType.RUN_SCRIPT || current.type() == DecisionType.FIX_LAST_SCRIPT)) {
                ScriptEntry version = library.addVersion(lastFailed, current.pythonCode());
                log.info("Fix: saved version {} of script '{}'", version.version(), version.displayName());
                resolved = Optional.of(version);
            } else {
                resolved = resolveScript(message, current);
            }
            if (resolved.isEmpty()) {
                return AgentReply.text("Не нашёл скрипт, о котором говорит модель. Попробуйте заново.");
            }
            ScriptEntry script = resolved.get();
            String code = library.codeOf(script).orElse(current.pythonCode());

            if (script.requiresConfirmation() && fixAttempt == 0) {
                String token = confirmations.put(message, script, code, budget, attempts);
                log.atInfo()
                        .addKeyValue("event", "confirmation.requested")
                        .addKeyValue("script_id", script.id())
                        .addKeyValue("token", token)
                        .log("Confirmation required for '{}'", script.displayName());
                return new AgentReply.NeedsConfirmation(token, script, describe(script, current));
            }

            Step step = runOnce(message, script, code, budget, attempts);
            if (step instanceof Step.Done done) {
                return done.reply();
            }
            current = ((Step.Retry) step).decision();
            lastFailed = script;
            fixAttempt++;
        }
    }

    private AgentReply continueFrom(UserMessage message, Step step, RequestBudget budget, int attempt,
                                    ScriptEntry failed, TaskAttempts attempts) {
        if (step instanceof Step.Done done) {
            return done.reply();
        }
        return loop(message, ((Step.Retry) step).decision(), budget, attempt, failed, attempts);
    }

    private Step runOnce(UserMessage message, ScriptEntry script, String code, RequestBudget budget,
                         TaskAttempts attempts) {
        ActionResult result;

        try (AutoCloseable busy = activity.busy("script '" + script.displayName() + "'", scripts.timeout())) {
            result = scripts.run(code);
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
        library.recordRun(script.id(), result.isSuccess());
        log.atInfo()
                .addKeyValue("event", "script.result")
                .addKeyValue("script_id", script.id())
                .addKeyValue("version", script.version())
                .addKeyValue("exit_code", result.exitCode())
                .addKeyValue("success", result.isSuccess())
                .addKeyValue("duration_ms", result.duration().toMillis())
                .log("Script '{}': exit={}", script.displayName(),
                        result.isTimeout() ? "timeout" : result.exitCode());

        if (!result.installedPackages().isEmpty()) {
            log.info("Installed before retry: {}", result.installedPackages());
        }

        if (result.isSuccess()) {
            attempts.succeed();
            rememberExecuted(message, script, result.stdout());
            return new Step.Done(replyFromText(summarize(message, result, budget)));
        }

        if (result.exitCode() == ActionResult.LAUNCH_FAILED_EXIT_CODE) {

            log.atWarn().addKeyValue("event", "script.launch_failed").addKeyValue("script_id", script.id())
                    .log("Script '{}' failed to launch: {}", script.displayName(), result.stderr());
            return new Step.Done(replyFromText("Не удалось выполнить действие: " + result.stderr().strip()));
        }
        // Read before recording: "already tried" means the rounds before this one. What has just
        // failed is right above it in the prompt, as the code and the error it produced.
        String alreadyTried = attempts.describeForModel();
        int attemptNumber = attempts.size() + 1;
        attempts.record(script.displayName() + " v" + script.version(),
                result.isTimeout() ? "timeout" : result.stderr());

        log.atInfo()
                .addKeyValue("event", "task.attempt_failed")
                .addKeyValue("script_id", script.id())
                .addKeyValue("attempts", attempts.size())
                .log("Script '{}' failed (exit={}), asking the model to fix it (attempt {})",
                        script.displayName(), result.isTimeout() ? "timeout" : result.exitCode(),
                        attemptNumber);
        budget.spend("script fix");

        LlmResponse response = ask(
                DecisionProtocol.decisionPrompt(tools, scriptsAllowed()) + personaBlock(),
                DecisionProtocol.fixPrompt(message.text(), code, result.describeForModel(),
                        attemptNumber, alreadyTried),
                true);
        AgentDecision fixed = AgentDecision.parse(response.text(), MAPPER);
        log.atInfo()
                .addKeyValue("event", "decision.fix")
                .addKeyValue("type", fixed.type().name())
                .log("Decision after fix: {}", fixed);
        return new Step.Retry(fixed);
    }

    private Optional<ScriptEntry> resolveScript(UserMessage message, AgentDecision decision) {
        return switch (decision.type()) {
            case USE_SCRIPT -> {
                Optional<ScriptEntry> entry = library.byId(decision.scriptId());
                entry.ifPresent(found -> log.info("Using a ready script from the catalog: {}", found));
                yield entry;
            }
            case FIX_LAST_SCRIPT -> lastScriptOf(message)
                    .map(previous -> library.addVersion(previous, decision.pythonCode()))
                    .or(() -> Optional.of(store(decision)));
            case RUN_SCRIPT -> Optional.of(store(decision));
            default -> Optional.empty();
        };
    }

    private ScriptEntry store(AgentDecision decision) {
        String name = decision.scriptName().isEmpty() ? "Unnamed script" : decision.scriptName();
        ScriptEntry entry = library.add(name, decision.explanation(), decision.scriptTags(),
                decision.pythonCode());
        log.info("New script in the catalog: {}", entry);
        return entry;
    }

    private AgentDecision decide(UserMessage message, DialogSession session, RequestBudget budget) {

        boolean scripts = scriptsAllowed();

        List<ScriptEntry> candidates = scripts ? library.search(message.text()) : List.of();
        ScriptEntry last = scripts ? lastScriptOf(message).orElse(null) : null;
        if (!candidates.isEmpty()) {
            log.info("Catalog candidates: {}",
                    candidates.stream().map(ScriptEntry::displayName).toList());
        }

        MemoryRecall.Selection remembered = recall.select(message.text(), clock.instant());
        logRecall(remembered);
        String memoryBlock = remembered.block();
        List<LlmMessage> history = historyOf(session);

        budget.spend("request decision");

        String userBlock = message.text()
                + DecisionProtocol.contextBlock(candidates, last)
                + (tools.find("web_search").isPresent() ? FreshnessHints.hintFor(message.text()) : "");

        String system = DecisionProtocol.decisionPrompt(tools, scripts) + memoryBlock + DecisionProtocol.nowBlock(clock)
                + (reminders == null ? "" : reminders.promptBlock())
                + (live() ? DecisionProtocol.liveRepliesBlock() : "") + personaBlock();
        // The picture goes with the decision call and only with it: it is context for this one
        // question, not something the agent keeps or builds a script out of.
        boolean offeredFacts = !remembered.offered().isEmpty();
        LlmResponse response = askStructured(system, history, userBlock, scripts, message.images(), offeredFacts);

        AgentDecision decision = AgentDecision.parse(response.text(), MAPPER);
        noteUsedFacts(decision, remembered);
        if (!scripts && (decision.type().isScript() || !decision.isUsable())) {

            log.atWarn().addKeyValue("event", "decision.scripts_disabled").addKeyValue("type", decision.type().name())
                    .log("Model did not follow the no-scripts protocol ({}) -- asking for a plain answer", decision.type());
            decision = plainReply(message, history, budget);
        }
        if (!decision.isUsable() && scripts) {

            // One reminder of the format, and only then give up. Before this the loop went
            // straight to "не разобрался" -- and any path that instead forwarded the raw text
            // put the protocol JSON in front of the user.
            decision = retryWithFormatReminder(system, history, userBlock, response.text(), budget, offeredFacts);
        }
        log.atInfo()
                .addKeyValue("event", "decision")
                .addKeyValue("type", decision.type().name())
                .addKeyValue("script_id", decision.scriptId())
                .addKeyValue("candidates", candidates.size())
                .addKeyValue("model", response.model())
                .log("Model decision: {}", decision);
        return decision;
    }

    /**
     * Asks once more, saying plainly what was wrong with the previous answer.
     *
     * <p>The raw answer is quoted back to the model and written to the log, and it goes nowhere
     * else: whatever the model produced when it ignored the protocol is internal by definition.
     *
     * @return a usable decision, or {@link AgentDecision#unknown()} -- which the loop turns into
     *         a neutral sentence
     */
    private AgentDecision retryWithFormatReminder(String system, List<LlmMessage> history,
                                                  String userBlock, String rawAnswer,
                                                  RequestBudget budget, boolean memoryFacts) {
        log.atWarn()
                .addKeyValue("event", "decision.unparsed")
                .addKeyValue("answer", rawAnswer)
                .log("Model returned a decision that cannot be read -- asking again with the format spelled out");

        if (!budget.trySpend("decision format reminder")) {
            log.warn("No budget left for a second attempt at the decision");
            return AgentDecision.unknown();
        }
        LlmResponse retry = askStructured(system, history,
                userBlock + "\n\n" + DecisionProtocol.formatReminderPrompt(rawAnswer), true,
                List.of(), memoryFacts);
        AgentDecision second = AgentDecision.parse(retry.text(), MAPPER);
        if (second.isUsable()) {
            log.atInfo().addKeyValue("event", "decision.reparsed")
                    .addKeyValue("type", second.type().name())
                    .log("The second attempt was readable: {}", second.type());
            return second;
        }
        log.atWarn()
                .addKeyValue("event", "decision.unparsed_twice")
                .addKeyValue("answer", retry.text())
                .log("The second attempt could not be read either -- answering neutrally");
        return AgentDecision.unknown();
    }

    private String summarize(UserMessage message, ActionResult result, RequestBudget budget) {
        if (!budget.trySpend("answer formulation")) {

            log.warn("No budget left for formulation, returning raw script output");
            return result.stdout().isBlank()
                    ? DecisionProtocol.budgetExhaustedMessage(budget)
                    : result.stdout().strip();
        }

        LlmResponse response = ask(
                replySystemPrompt(),
                DecisionProtocol.summaryPrompt(message.text(), result.stdout()),
                false);

        String text = response.text().strip();
        log.atInfo()
                .addKeyValue("event", "reply.ready")
                .addKeyValue("length", text.length())
                .log("Answer formulated");
        return text.isEmpty() ? result.stdout().strip() : text;
    }

    private LlmResponse ask(String system, String user, boolean structured) {
        return ask(system, List.of(), user, structured);
    }

    private LlmResponse ask(String system, List<LlmMessage> history, String user, boolean structured) {
        return structured
                ? askStructured(system, history, user, scriptsAllowed())
                : askBusy(() -> provider().chat(new LlmRequest(system, history, user, null, null)));
    }

    private LlmResponse askStructured(String system, List<LlmMessage> history, String user, boolean scripts) {
        return askStructured(system, history, user, scripts, List.of(), false);
    }

    private LlmResponse askStructured(String system, List<LlmMessage> history, String user, boolean scripts,
                                      List<com.bebebe.agent.llm.LlmImage> images, boolean memoryFacts) {
        LlmRequest request = new LlmRequest(system, history, user, null,
                DecisionProtocol.responseSchema(tools, live(), scripts, memoryFacts), images);
        return askBusy(() -> provider().chat(request));
    }

    private AgentDecision plainReply(UserMessage message, List<LlmMessage> history, RequestBudget budget) {
        if (!budget.trySpend("answer without schema")) {
            return AgentDecision.reply(DecisionProtocol.budgetExhaustedMessage(budget));
        }
        LlmResponse response = ask(replySystemPrompt(), history, message.text(), false);
        String text = response.text().strip();
        if (text.isEmpty()) {
            return AgentDecision.reply(DecisionProtocol.scriptsDisabledMessage());
        }
        return replyFromText(text) instanceof AgentReply.Text parts && parts.isMultipart()
                ? AgentDecision.reply(text, parts.parts())
                : AgentDecision.reply(text);
    }

    private LlmResponse askBusy(Supplier<LlmResponse> call) {
        try (AutoCloseable busy = activity.busy("model reply", provider().timeout().plusSeconds(5))) {
            return call.get();
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private AgentReply runTool(UserMessage message, AgentDecision decision, RequestBudget budget) {
        log.atInfo()
                .addKeyValue("event", "tool.call")
                .addKeyValue("tool", decision.toolName())
                .addKeyValue("arguments", decision.arguments().toString())
                .log("Tool {} {}", decision.toolName(), decision.arguments());

        ToolContext context = new ToolContext(budgetedLlm(budget), message.text());
        ToolResult result;
        try {
            result = tools.execute(decision.toolName(), decision.arguments(), context);
        } catch (ToolContext.BudgetExhausted e) {
            return answerWithoutTool(message, decision.toolName(), budget);
        }

        if (result.contextSource() != null) {

            memory.activeSession(conversationKey(message)).ifPresent(session ->
                    memory.append(session.id(), MessageRole.USER, result.content(),
                            result.contextSource(), message.traceId()));
        }

        if (!budget.trySpend("answer formulation from tool result")) {

            // describeForModel() is written for the model -- labels, truncation marks, error
            // text -- so it goes to the log, and the user gets the honest neutral sentence.
            log.atWarn().addKeyValue("event", "tool.unformulated")
                    .addKeyValue("result", result.describeForModel())
                    .log("No budget left to formulate an answer from the tool result");
            return AgentReply.text(DecisionProtocol.budgetExhaustedMessage(budget));
        }
        LlmResponse response = ask(
                replySystemPrompt(),
                DecisionProtocol.toolSummaryPrompt(message.text(), decision.toolName(), result.describeForModel()),
                false);
        String text = response.text().strip();
        log.atInfo().addKeyValue("event", "reply.ready").addKeyValue("length", text.length()).log("Answer formulated");
        if (text.isEmpty()) {
            log.atWarn().addKeyValue("event", "tool.unformulated")
                    .addKeyValue("result", result.describeForModel())
                    .log("The model returned an empty answer for the tool result");
            return AgentReply.text(LeakGuard.neutralReply());
        }
        return replyFromText(text);
    }

    private static final int RESERVED_FOR_REPLY = 1;

    private ToolContext.LlmCaller budgetedLlm(RequestBudget budget) {
        return (system, user, schema) -> {
            if (budget.remaining() <= RESERVED_FOR_REPLY || !budget.trySpend("tool: model call")) {
                throw new ToolContext.BudgetExhausted("Request budget exhausted inside a tool");
            }
            LlmRequest request = new LlmRequest(system, List.of(), user, schema == null ? 0.3 : 0.1, schema);
            return askBusy(() -> provider().chat(request)).text();
        };
    }

    private AgentReply answerWithoutTool(UserMessage message, String toolName, RequestBudget budget) {
        log.atWarn()
                .addKeyValue("event", "tool.budget_exhausted")
                .addKeyValue("tool", toolName)
                .addKeyValue("budget_used", budget.used())
                .log("Tool {} cut off by the budget, answering without it", toolName);
        if (!budget.trySpend("answer without tool result")) {
            return AgentReply.text(DecisionProtocol.budgetExhaustedMessage(budget));
        }
        List<LlmMessage> history = memory.activeSession(conversationKey(message))
                .map(this::historyOf).orElse(List.of());
        LlmResponse response = ask(replySystemPrompt(), history,
                DecisionProtocol.withoutToolPrompt(message.text(), toolName), false);
        String text = response.text().strip();
        log.atInfo().addKeyValue("event", "reply.ready").addKeyValue("length", text.length())
                .addKeyValue("without_tool", toolName).log("Answer formulated without the tool");
        return text.isEmpty() ? AgentReply.text(DecisionProtocol.budgetExhaustedMessage(budget)) : replyFromText(text);
    }

    private List<LlmMessage> historyOf(DialogSession session) {
        List<DialogMessage> all = memory.messages(session.id());
        List<LlmMessage> history = new ArrayList<>();
        for (int i = 0; i < all.size() - 1; i++) {
            DialogMessage m = all.get(i);
            history.add(m.role() == MessageRole.USER
                    ? LlmMessage.user(m.text())
                    : LlmMessage.assistant(m.text()));
        }
        return history;
    }

    private void logRecall(MemoryRecall.Selection selection) {
        if (!selection.mentioned().isEmpty()) {
            log.atInfo()
                    .addKeyValue("event", "memory.resolved")
                    .addKeyValue("entities", selection.mentioned().stream()
                            .map(Entity::canonicalName).toList().toString())
                    .log("Mentioned: {}", selection.mentioned().stream().map(Entity::canonicalName).toList());
        }
        if (!selection.recalled().isEmpty()) {
            log.atInfo()
                    .addKeyValue("event", "memory.recalled")
                    .addKeyValue("count", selection.recalled().size())
                    .log("Recalled by keyword: {}", selection.recalled().stream().map(Fact::text).toList());
        }
        if (!selection.episodes().isEmpty()) {
            log.atDebug()
                    .addKeyValue("event", "memory.episodes")
                    .addKeyValue("count", selection.episodes().size())
                    .log("Past conversations offered: {}", selection.episodes().size());
        }
    }

    /**
     * Records which remembered facts the model says it used.
     *
     * <p>Only numbers that were actually shown this turn count. The model invents ids readily
     * enough, and an invented one would teach the ranking that a fact nobody has read is the most
     * useful thing in memory.
     */
    private void noteUsedFacts(AgentDecision decision, MemoryRecall.Selection offered) {
        if (decision.usedFacts().isEmpty() || offered.offered().isEmpty()) {
            return;
        }
        List<Long> real = decision.usedFacts().stream().filter(offered.offered()::contains).toList();
        if (real.isEmpty()) {
            log.atDebug().addKeyValue("event", "memory.used_unknown")
                    .addKeyValue("claimed", decision.usedFacts().toString())
                    .log("The model named facts it was not shown -- ignored");
            return;
        }
        memory.markUsed(real);
        log.atInfo()
                .addKeyValue("event", "memory.used")
                .addKeyValue("facts", real.toString())
                .addKeyValue("offered", offered.offered().size())
                .log("The answer leaned on {} of the {} facts offered", real.size(), offered.offered().size());
    }

    /**
     * Everything a finished conversation still owes: its tail extracted, and a summary of itself.
     *
     * <p>Runs off the worker thread, because closing happens while the user is waiting for the
     * first answer of the <i>next</i> conversation and neither of these is worth making them wait
     * for. The tail matters more than it looks: a session closed by idle used to take its
     * unextracted tail with it, since the periodic sweep only ever looks at sessions that are
     * still open.
     */
    private void closeOut(DialogSession session) {
        if (!agentSwitch.isOn()) {

            // Switched off: the stop hook has already extracted and summarised this session by
            // hand, on the thread that could still be sure of finishing.
            return;
        }
        try (TraceContext.Scope ignored = TraceContext.open("mem-" + session.id())) {
            DialogSession fresh = memory.session(session.id()).orElse(session);
            if (fresh.hasSummary()) {
                return;
            }
            if (!memory.unconsolidated(fresh).isEmpty()) {
                deliverQuestions(consolidator.consolidate(fresh), fresh.conversationKey());
            }
            consolidator.summarize(fresh).ifPresent(summary ->
                    log.atInfo()
                            .addKeyValue("event", "memory.episode")
                            .addKeyValue("session_id", fresh.id())
                            .log("Session {} closed: {}", fresh.id(), summary.replace("\n", " / ")));
        } catch (RuntimeException e) {
            log.error("Could not close out session {}", session.id(), e);
        }
    }

    /**
     * Consolidation on a timer, on top of the message counter.
     *
     * <p>The counter only fires while the conversation keeps going: the tail of a conversation
     * that simply stops -- the user walked away, the worker hung and the watchdog restarted it,
     * the machine lost power -- waited for {@code onBeforeStop} and was lost with it whenever the
     * process did not stop cleanly. A few minutes of idle memory is cheap; a whole evening of
     * conversation is not.
     */
    private final class PeriodicConsolidation implements AutoCloseable {

        private final java.util.concurrent.ScheduledExecutorService timer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "memory-consolidation");
                    t.setDaemon(true);
                    return t;
                });

        private java.util.concurrent.ScheduledFuture<?> ticking;

        private java.util.concurrent.ScheduledFuture<?> gardening;

        /**
         * Background memory work that is not on a timer -- closing a session out. Shares this
         * thread on purpose: consolidation of one session must not run twice at once, and one
         * queue is the simplest way to guarantee it.
         */
        void submit(Runnable task) {
            try {
                timer.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.debug("Memory work rejected: the agent is shutting down");
            }
        }

        synchronized void start(java.time.Duration every) {
            if (every.isZero() || ticking != null) {
                if (every.isZero()) {
                    log.info("Periodic consolidation is off (memory.consolidate_minutes = 0)");
                }
                return;
            }
            long seconds = Math.max(30, every.toSeconds());
            ticking = timer.scheduleWithFixedDelay(this::sweepSafely, seconds, seconds,
                    java.util.concurrent.TimeUnit.SECONDS);
            log.info("Periodic consolidation: every {} min", every.toMinutes());
        }

        /**
         * Going over memory as a whole, on the same single thread as everything else here: two
         * passes rewriting the same facts at once is not a race worth having.
         */
        synchronized void startGardening(java.time.Duration every) {
            if (every.isZero() || gardening != null) {
                if (every.isZero()) {
                    log.info("Memory gardening is off (memory.gardening_hours = 0)");
                }
                return;
            }

            // Not immediately at start-up: the first minutes after switching on are the busiest,
            // and a day-scale job has no reason to be in the way.
            long seconds = Math.max(60, every.toSeconds());
            gardening = timer.scheduleWithFixedDelay(this::tendSafely, Math.min(600, seconds), seconds,
                    java.util.concurrent.TimeUnit.SECONDS);
            log.info("Memory gardening: every {} h", every.toHours());
        }

        private void tendSafely() {
            if (!agentSwitch.isOn()) {
                return;
            }
            try (TraceContext.Scope ignored = TraceContext.open("garden-" + System.nanoTime() % 0xffffff)) {
                gardener.tend();
            } catch (RuntimeException e) {
                log.error("Memory gardening failed", e);
            }
        }

        /** Stopped before the switch-off hook consolidates, so the two never run at once. */
        synchronized void pause() {
            if (ticking != null) {
                ticking.cancel(false);
                ticking = null;
            }
            if (gardening != null) {
                gardening.cancel(false);
                gardening = null;
            }
        }

        private void sweepSafely() {
            try {
                sweep();
            } catch (RuntimeException e) {
                log.error("Periodic consolidation failed", e);
            }
        }

        private void sweep() {
            if (!agentSwitch.isOn()) {
                return;
            }
            for (DialogSession session : memory.activeSessions()) {
                DialogSession fresh = memory.session(session.id()).orElse(session);
                int pending = memory.unconsolidated(fresh).size();
                if (pending == 0) {
                    continue;
                }
                try (TraceContext.Scope ignored = TraceContext.open("mem-" + fresh.id())) {
                    log.atInfo()
                            .addKeyValue("event", "memory.sweep")
                            .addKeyValue("session_id", fresh.id())
                            .addKeyValue("messages", pending)
                            .log("Periodic consolidation of session {}: {} messages", fresh.id(), pending);
                    deliverQuestions(consolidator.consolidate(fresh), fresh.conversationKey());
                }
            }

            // Whatever consolidation has just written still has no vector; this is the one place
            // that sees every new fact regardless of which path created it.
            embedPending();
        }

        @Override
        public void close() {
            timer.shutdownNow();
        }
    }

    private final class MemoryConsolidationHook implements AgentLifecycleHook {

        @Override
        public String name() {
            return "memory-consolidation";
        }

        @Override
        public void onBeforeStop() {

            sweeper.pause();
            int active = memory.activeSessions().size();
            if (active == 0) {
                log.debug("[hook {}] no active sessions", name());
                return;
            }
            log.info("[hook {}] consolidating {} active sessions before stopping", name(), active);
            for (DialogSession session : memory.activeSessions()) {
                try (TraceContext.Scope ignored = TraceContext.open("mem-" + session.id())) {
                    deliverQuestions(consolidator.consolidate(session), session.conversationKey());

                    // Synchronously, and before the session is closed: the listener that normally
                    // does this hands the work to a thread that is about to be shut down.
                    consolidator.summarize(session);
                    memory.endSession(session.id());
                    log.info("Session {} ({}) consolidated and closed",
                            session.id(), session.conversationKey());
                }
            }
        }

        @Override
        public void onAfterStart() {
            log.info("[hook {}] memory: {} entities, {} facts",
                    name(), memory.countEntities(), memory.countFacts());
            sweeper.start(memory.config().consolidateInterval());
            sweeper.startGardening(memory.config().gardenInterval());
            sweeper.submit(AgentCore.this::embedPending);
        }
    }

    private void rememberExecuted(UserMessage message, ScriptEntry script, String output) {
        String key = conversationKey(message);
        lastExecuted.put(key, script.rootId());
        lastOutput.put(key, output == null ? "" : output.strip());
        log.debug("Remembered last script for {}: {}", key, script);
    }

    private Optional<ScriptEntry> lastScriptOf(UserMessage message) {
        Long rootId = lastExecuted.get(conversationKey(message));
        if (rootId == null) {
            return Optional.empty();
        }

        List<ScriptEntry> versions = library.allVersionsOf(rootId);
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
    }

    public String lastOutputOf(UserMessage message) {
        return lastOutput.getOrDefault(conversationKey(message), "");
    }

    private static String conversationKey(UserMessage message) {
        return message.source().name() + ":" + message.replyTarget().orElse("-");
    }

    private static String describe(ScriptEntry script, AgentDecision decision) {
        String explanation = decision.explanation().isEmpty()
                ? script.description()
                : decision.explanation();
        return explanation.isEmpty() ? "No description." : explanation;
    }
}
