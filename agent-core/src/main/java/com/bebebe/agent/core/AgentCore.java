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
import java.util.LinkedHashMap;
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
    private final EntityResolver entityResolver;
    private final ToolRegistry tools;
    private final ReminderService reminders;

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
        this.entityResolver = new EntityResolver(memory);
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

    private String personaBlock() {
        com.bebebe.agent.memory.PersonaStore store = personas;
        return store == null ? "" : store.promptBlock();
    }

    private String replySystemPrompt() {
        return "You are a personal AI agent. Answer briefly, in Russian."
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
                AgentReply reply = guarded(budget, () -> loop(message, decide(message, session, budget), budget, 0));
                afterReply(message, session, reply);
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
                Step step = runOnce(pending.message(), pending.script(), pending.code(), pending.budget());
                return continueFrom(pending.message(), step, pending.budget(), 1, pending.script());
            });
            memory.activeSession(conversationKey(pending.message()))
                    .ifPresent(session -> afterReply(pending.message(), session, reply));
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
            log.error("Model unavailable: {}", e.getMessage());
            return AgentReply.text("Не получилось обратиться к модели: " + e.getMessage());
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

    private AgentReply loop(UserMessage message, AgentDecision decision, RequestBudget budget, int attempt) {
        return loop(message, decision, budget, attempt, null);
    }

    private AgentReply loop(UserMessage message, AgentDecision decision, RequestBudget budget, int attempt,
                            ScriptEntry failed) {
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
                String token = confirmations.put(message, script, code, budget);
                log.atInfo()
                        .addKeyValue("event", "confirmation.requested")
                        .addKeyValue("script_id", script.id())
                        .addKeyValue("token", token)
                        .log("Confirmation required for '{}'", script.displayName());
                return new AgentReply.NeedsConfirmation(token, script, describe(script, current));
            }

            Step step = runOnce(message, script, code, budget);
            if (step instanceof Step.Done done) {
                return done.reply();
            }
            current = ((Step.Retry) step).decision();
            lastFailed = script;
            fixAttempt++;
        }
    }

    private AgentReply continueFrom(UserMessage message, Step step, RequestBudget budget, int attempt) {
        return continueFrom(message, step, budget, attempt, null);
    }

    private AgentReply continueFrom(UserMessage message, Step step, RequestBudget budget, int attempt,
                                    ScriptEntry failed) {
        if (step instanceof Step.Done done) {
            return done.reply();
        }
        return loop(message, ((Step.Retry) step).decision(), budget, attempt, failed);
    }

    private Step runOnce(UserMessage message, ScriptEntry script, String code, RequestBudget budget) {
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
            rememberExecuted(message, script, result.stdout());
            return new Step.Done(replyFromText(summarize(message, result, budget)));
        }

        if (result.exitCode() == ActionResult.LAUNCH_FAILED_EXIT_CODE) {

            log.atWarn().addKeyValue("event", "script.launch_failed").addKeyValue("script_id", script.id())
                    .log("Script '{}' failed to launch: {}", script.displayName(), result.stderr());
            return new Step.Done(replyFromText("Не удалось выполнить действие: " + result.stderr().strip()));
        }
        log.info("Script '{}' failed (exit={}), asking the model to fix it",
                script.displayName(), result.isTimeout() ? "timeout" : result.exitCode());
        budget.spend("script fix");

        LlmResponse response = ask(
                DecisionProtocol.decisionPrompt(tools, scriptsAllowed()) + personaBlock(),
                DecisionProtocol.fixPrompt(message.text(), code, result.describeForModel(), 1),
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

        String memoryBlock = memoryContext(message);
        List<LlmMessage> history = historyOf(session);

        budget.spend("request decision");

        String userBlock = message.text()
                + DecisionProtocol.contextBlock(candidates, last)
                + (tools.find("web_search").isPresent() ? FreshnessHints.hintFor(message.text()) : "");

        String system = DecisionProtocol.decisionPrompt(tools, scripts) + memoryBlock + DecisionProtocol.nowBlock(clock)
                + (reminders == null ? "" : reminders.promptBlock())
                + (live() ? DecisionProtocol.liveRepliesBlock() : "") + personaBlock();
        LlmResponse response = askStructured(system, history, userBlock, scripts);

        AgentDecision decision = AgentDecision.parse(response.text(), MAPPER);
        if (!scripts && (decision.type().isScript() || !decision.isUsable())) {

            log.atWarn().addKeyValue("event", "decision.scripts_disabled").addKeyValue("type", decision.type().name())
                    .log("Model did not follow the no-scripts protocol ({}) -- asking for a plain answer", decision.type());
            decision = plainReply(message, history, budget);
        }
        if (decision.type() == DecisionType.UNKNOWN) {

            String raw = response.text();
            log.warn("Model returned an unparseable decision: {}",
                    raw.length() > 600 ? raw.substring(0, 600) + "…" : raw);
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
        LlmRequest request = new LlmRequest(system, history, user, null,
                DecisionProtocol.responseSchema(tools, live(), scripts));
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
            log.warn("No budget left for formulation, returning the tool result as is");
            return AgentReply.text(result.describeForModel());
        }
        LlmResponse response = ask(
                replySystemPrompt(),
                DecisionProtocol.toolSummaryPrompt(message.text(), decision.toolName(), result.describeForModel()),
                false);
        String text = response.text().strip();
        log.atInfo().addKeyValue("event", "reply.ready").addKeyValue("length", text.length()).log("Answer formulated");
        return text.isEmpty() ? AgentReply.text(result.describeForModel()) : replyFromText(text);
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

    private String memoryContext(UserMessage message) {
        List<Entity> mentioned = entityResolver.resolve(message.text());
        Map<Entity, List<Fact>> facts = new LinkedHashMap<>();
        for (Entity entity : mentioned) {
            facts.put(entity, memory.factsOf(entity.id()));
        }
        if (!mentioned.isEmpty()) {
            log.atInfo()
                    .addKeyValue("event", "memory.resolved")
                    .addKeyValue("entities", mentioned.stream().map(Entity::canonicalName).toList().toString())
                    .log("Mentioned: {}", mentioned.stream().map(Entity::canonicalName).toList());
        }
        return MemoryProtocol.contextBlock(
                mentioned, facts, memory.factsAboutUser(), memory.factsByCategory(FactCategory.PROCEDURE));
    }

    private final class MemoryConsolidationHook implements AgentLifecycleHook {

        @Override
        public String name() {
            return "memory-consolidation";
        }

        @Override
        public void onBeforeStop() {
            int active = memory.activeSessions().size();
            if (active == 0) {
                log.debug("[hook {}] no active sessions", name());
                return;
            }
            log.info("[hook {}] consolidating {} active sessions before stopping", name(), active);
            for (DialogSession session : memory.activeSessions()) {
                try (TraceContext.Scope ignored = TraceContext.open("mem-" + session.id())) {
                    deliverQuestions(consolidator.consolidate(session), session.conversationKey());
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
