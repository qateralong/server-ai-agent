package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.config.ConfigException;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.core.AgentState;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.Persona;
import com.bebebe.agent.memory.PersonaStore;
import com.bebebe.agent.scheduler.Job;
import com.bebebe.agent.notes.NoteDocument;
import com.bebebe.agent.notes.NoteKind;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.scheduler.JobStore;
import com.bebebe.agent.script.library.ScriptEntry;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.input.InputOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class MenuController {

    private static final Logger log = LoggerFactory.getLogger(MenuController.class);

    private final AgentSwitch agentSwitch;
    private final AppSettings settings;
    private final Supplier<List<String>> modelCatalog;
    private final ScriptLibrary library;
    private final ConfirmationActions confirmActions;
    private final MemoryStore memory;
    private final MemoryActions memoryActions;

    private volatile JobStore jobs;
    private volatile java.time.ZoneId zone = java.time.ZoneId.systemDefault();

    public void attachJobs(JobStore jobs, java.time.ZoneId zone) {
        this.jobs = jobs;
        this.zone = zone;
    }

    private volatile com.bebebe.agent.notes.NotesStore notes;

    public void attachNotes(com.bebebe.agent.notes.NotesStore notes) {
        this.notes = notes;
    }

    private volatile PersonaStore personas;

    public void attachPersonas(PersonaStore personas) {
        this.personas = personas;
    }

    public interface MemoryActions {

        AgentReply resolveEntity(String token, boolean sameAsCandidate);
    }

    public interface ConfirmationActions {

        AgentReply confirm(String token);

        AgentReply cancel(String token);

        AgentReply trust(long scriptId);
    }

    private volatile List<String> lastModels = List.of();

    public MenuController(AgentSwitch agentSwitch,
                          AppSettings settings,
                          Supplier<List<String>> modelCatalog,
                          ScriptLibrary library,
                          ConfirmationActions confirmActions,
                          MemoryStore memory,
                          MemoryActions memoryActions) {
        this.agentSwitch = agentSwitch;
        this.settings = settings;
        this.modelCatalog = modelCatalog;
        this.library = library;
        this.confirmActions = confirmActions;
        this.memory = memory;
        this.memoryActions = memoryActions;
    }

    public MenuResponse handle(CallbackData data) {
        return handle(data, "");
    }

    public MenuResponse handle(CallbackData data, String conversationKey) {
        return switch (data.namespace()) {
            case CallbackData.NS_NAV -> navigate(data);
            case CallbackData.NS_MENU -> openSection(data);
            case CallbackData.NS_POWER -> power(data);
            case CallbackData.NS_SETTINGS -> settings(data);
            case CallbackData.NS_CONFIRM -> confirmations(data);
            case CallbackData.NS_MEMORY -> memorySection(data, conversationKey);
            case CallbackData.NS_REMINDERS -> reminders(data);
            case CallbackData.NS_NOTES -> notes(data);
            case CallbackData.NS_PERSONAS -> personaSection(data);
            case CallbackData.NS_LOGS -> logsSection(data);
            default -> {
                log.warn("Unknown namespace in callback_data: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    public MenuScreen screenFor(MenuSection section) {
        return switch (section) {
            case SETTINGS -> SettingsScreens.root(settings, readOnlySettings);
            case STATUS -> serverStatus == null ? StatusScreens.desktopStub() : StatusScreens.root(serverStatus.get());
            case LOGS -> logBuffer == null ? LogScreens.desktopStub()
                    : LogScreens.page(logBuffer, LogScreens.Filter.WARNINGS, 0, logDir);
            case CONFIRMATIONS -> ConfirmScreens.list(library.latestVersions(), 0);
            case MEMORY -> MemoryScreens.root(memory);
            case REMINDERS -> jobs == null
                    ? MenuRenderer.stub(MenuSection.REMINDERS)
                    : ReminderScreens.root(jobs.countPending(), jobs.history().size());
            case NOTES -> notes == null ? MenuRenderer.stub(MenuSection.NOTES) : notesRoot();
            case PERSONAS -> personas == null ? MenuRenderer.stub(MenuSection.PERSONAS) : PersonaScreens.list(personas.all());
            default -> MenuRenderer.section(section, state());
        };
    }

    public MenuScreen rootScreen() {
        return MenuRenderer.root(state(), visibleSections());
    }

    private MenuResponse logsSection(CallbackData data) {
        if (logBuffer == null) {
            return MenuResponse.show(LogScreens.desktopStub());
        }
        LogScreens.Filter filter = LogScreens.Filter.byCode(data.action());
        int page = data.arg(1).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return 0;
            }
        }).orElse(0);
        return MenuResponse.show(LogScreens.page(logBuffer, filter, page, logDir));
    }

    private MenuResponse navigate(CallbackData data) {
        return switch (data.action()) {
            case "root" -> MenuResponse.show(rootScreen());
            case "close" -> MenuResponse.show(MenuRenderer.closed());
            case "noop" -> MenuResponse.toast(null);
            default -> {
                log.warn("Unknown navigation action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    private MenuResponse openSection(CallbackData data) {
        Optional<MenuSection> section = MenuSection.byId(data.action());
        if (section.isEmpty()) {
            log.warn("Unknown menu section: {}", data);
            return MenuResponse.toast("No such section");
        }
        return MenuResponse.show(screenFor(section.get()));
    }

    private volatile Runnable showWindow;

    private volatile java.util.function.Supplier<ServerStatus> serverStatus;
    private volatile com.bebebe.agent.logging.LogBuffer logBuffer;
    private volatile String logDir = "logs";
    private volatile boolean readOnlySettings;

    public void attachHeadless(java.util.function.Supplier<ServerStatus> status,
                               com.bebebe.agent.logging.LogBuffer logs, String logDir) {
        this.serverStatus = status;
        this.logBuffer = logs;
        this.logDir = logDir == null ? "logs" : logDir;
        this.readOnlySettings = true;
    }

    public boolean isHeadless() {
        return serverStatus != null;
    }

    public List<MenuSection> visibleSections() {
        return java.util.Arrays.stream(MenuSection.values())
                .filter(s -> s != MenuSection.LOGS || isHeadless())
                .toList();
    }

    public void attachWindow(Runnable showWindow) {
        this.showWindow = showWindow;
    }

    private MenuResponse power(CallbackData data) {
        if (data.action().equals("window")) {
            Runnable show = showWindow;
            if (show == null) {
                return MenuResponse.toast("Window unavailable");
            }
            show.run();
            return MenuResponse.toast("Window shown on the desktop");
        }
        if (!data.action().equals("set")) {
            log.warn("Unknown power action: {}", data);
            return MenuResponse.toast("Unknown button");
        }

        Optional<String> target = data.arg(1);
        if (target.isEmpty() || !(target.get().equals("on") || target.get().equals("off"))) {
            log.warn("Invalid switch target: {}", data);
            return MenuResponse.toast("Unknown button");
        }

        boolean turnOn = target.get().equals("on");
        boolean changed = agentSwitch.isOn() != turnOn;
        if (turnOn) {
            agentSwitch.turnOn();
        } else {
            agentSwitch.turnOff();
        }

        String toast = changed
                ? (turnOn ? "Agent switched on" : "Agent switched off")
                : "Already in this state";
        return MenuResponse.show(MenuRenderer.power(state()), toast);
    }

    private MenuResponse settings(CallbackData data) {
        if (readOnlySettings) {

            return MenuResponse.show(SettingsScreens.root(settings, true),
                    "Server mode: settings are edited in config.toml over SSH");
        }
        return switch (data.action()) {
            case "model" -> model(data);
            case "user" -> user(data);
            case "hints" -> hints(data);
            case "voice" -> voiceReplies(data);
            case "live" -> liveReplies(data);
            case "typing" -> typingIndicator(data);
            case "scripts" -> scriptsEnabled(data);
            case "prov" -> provider(data);
            default -> {
                log.warn("Unknown settings action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    private MenuResponse model(CallbackData data) {
        if (data.arg(1).isEmpty()) {

            List<String> models = fetchModels();
            lastModels = models;
            return MenuResponse.show(SettingsScreens.models(settings, models));
        }
        if (!data.arg(1).get().equals("pick")) {
            return MenuResponse.toast("Unknown button");
        }

        Optional<String> chosen = data.arg(2).flatMap(this::modelByIndex);
        if (chosen.isEmpty()) {

            log.warn("Model index not parsed or stale: {}", data);
            return MenuResponse.show(SettingsScreens.models(settings, lastModels),
                    "The list is stale, open it again");
        }

        settings.setModel(chosen.get());
        return MenuResponse.show(SettingsScreens.root(settings), persist("Model: " + chosen.get()));
    }

    private Optional<String> modelByIndex(String raw) {
        try {
            int index = Integer.parseInt(raw);
            List<String> models = lastModels;
            return index >= 0 && index < models.size() ? Optional.of(models.get(index)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private List<String> fetchModels() {
        try {
            return modelCatalog.get().stream().limit(SettingsScreens.MAX_MODELS).toList();
        } catch (RuntimeException e) {
            log.warn("Failed to fetch the model list: {}", e.getMessage());
            return List.of();
        }
    }

    private MenuResponse user(CallbackData data) {
        if (data.arg(1).isEmpty()) {
            return MenuResponse.show(SettingsScreens.usernames(settings));
        }

        return switch (data.arg(1).get()) {
            case "add" -> MenuResponse.awaitInput(
                    SettingsScreens.awaitingUsername(),
                    new InputRequest("telegram.allowed_usernames", this::acceptUsername));
            case "rm" -> removeUser(data);
            default -> MenuResponse.toast("Unknown button");
        };
    }

    private MenuResponse removeUser(CallbackData data) {
        List<String> current = settings.allowedUsernames();
        Optional<Integer> index = data.arg(2).flatMap(MenuController::parseIndex)
                .filter(i -> i >= 0 && i < current.size());
        if (index.isEmpty()) {
            return MenuResponse.show(SettingsScreens.usernames(settings), "The list is stale");
        }

        String removed = current.get(index.get());
        settings.removeAllowedUsername(removed);
        return MenuResponse.show(SettingsScreens.usernames(settings), persist("Removed @" + removed));
    }

    private InputOutcome acceptUsername(String value) {
        String normalized = AppSettings.normalizeUsername(value);
        if (normalized.isEmpty()) {
            return InputOutcome.rejected("Empty name. Send the username again or /cancel");
        }
        if (!normalized.matches("[a-z0-9_]{3,32}")) {
            return InputOutcome.rejected(
                    "This does not look like a Telegram username (latin letters, digits and underscore, 3-32 chars). "
                            + "Send it again or /cancel");
        }
        if (settings.allowedUsernames().contains(normalized)) {
            return InputOutcome.accepted("@" + normalized + " is already on the list",
                    CallbackData.userList());
        }

        settings.addAllowedUsername(normalized);
        return InputOutcome.accepted(persist("Added @" + normalized), CallbackData.userList());
    }

    private MenuResponse hints(CallbackData data) {
        Optional<String> target = data.arg(1);
        if (target.isEmpty() || !(target.get().equals("on") || target.get().equals("off"))) {
            return MenuResponse.toast("Unknown button");
        }

        boolean enable = target.get().equals("on");
        settings.setProactiveHints(enable);
        return MenuResponse.show(SettingsScreens.root(settings),
                persist(enable ? "Hints on" : "Hints off"));
    }

    private MenuResponse voiceReplies(CallbackData data) {
        Optional<String> target = data.arg(1);
        if (target.isEmpty() || !(target.get().equals("on") || target.get().equals("off"))) {
            return MenuResponse.toast("Unknown button");
        }
        boolean enable = target.get().equals("on");
        settings.setVoiceReplies(enable);
        return MenuResponse.show(SettingsScreens.root(settings),
                persist(enable ? "Voice replies on" : "Voice replies off"));
    }

    private MenuResponse provider(CallbackData data) {
        Optional<String> id = data.arg(1).filter(AppSettings.PROVIDERS::contains);
        if (id.isEmpty()) {
            return MenuResponse.toast("Unknown provider");
        }
        settings.setProvider(id.get());
        lastModels = List.of();
        String name = AppSettings.PROVIDER_CLAUDE.equals(id.get()) ? "Claude" : "Ollama";
        String warn = settings.apiKey().isEmpty() && !AppSettings.PROVIDER_OLLAMA.equals(id.get())
                ? " -- key not set, set it in the window" : "";
        return MenuResponse.show(SettingsScreens.root(settings), persist("Provider: " + name + warn));
    }

    private MenuResponse liveReplies(CallbackData data) {
        Optional<String> target = data.arg(1);
        if (target.isEmpty() || !(target.get().equals("on") || target.get().equals("off"))) {
            return MenuResponse.toast("Unknown button");
        }
        boolean enable = target.get().equals("on");
        settings.setLiveReplies(enable);
        return MenuResponse.show(SettingsScreens.root(settings),
                persist(enable ? "Lively style on" : "Lively style off"));
    }

    private MenuResponse scriptsEnabled(CallbackData data) {
        Optional<String> target = data.arg(1);
        if (target.isEmpty() || !(target.get().equals("on") || target.get().equals("off"))) {
            return MenuResponse.toast("Unknown button");
        }
        boolean enable = target.get().equals("on");
        settings.setScriptsEnabled(enable);
        return MenuResponse.show(SettingsScreens.root(settings),
                persist(enable ? "Scripts on" : "Scripts off"));
    }

    private MenuResponse typingIndicator(CallbackData data) {
        Optional<String> target = data.arg(1);
        if (target.isEmpty() || !(target.get().equals("on") || target.get().equals("off"))) {
            return MenuResponse.toast("Unknown button");
        }
        boolean enable = target.get().equals("on");
        settings.setTypingIndicator(enable);
        return MenuResponse.show(SettingsScreens.root(settings),
                persist(enable ? "\"Typing...\" on" : "\"Typing...\" off"));
    }

    private MenuResponse confirmations(CallbackData data) {
        return switch (data.action()) {
            case "run" -> data.arg(1)
                    .map(token -> reply(confirmActions.confirm(token)))
                    .orElseGet(() -> MenuResponse.toast("Unknown button"));
            case "no" -> data.arg(1)
                    .map(token -> reply(confirmActions.cancel(token)))
                    .orElseGet(() -> MenuResponse.toast("Unknown button"));
            case "trust" -> data.arg(1).flatMap(MenuController::parseId)
                    .map(id -> reply(confirmActions.trust(id)))
                    .orElseGet(() -> MenuResponse.toast("Unknown button"));
            case "list" -> MenuResponse.show(ConfirmScreens.list(
                    library.latestVersions(), data.arg(1).flatMap(MenuController::parsePage).orElse(0)));
            case "tgl" -> toggleConfirmation(data);
            default -> {
                log.warn("Unknown confirmation action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    private MenuResponse toggleConfirmation(CallbackData data) {
        Optional<Long> id = data.arg(1).flatMap(MenuController::parseId);
        int page = data.arg(2).flatMap(MenuController::parsePage).orElse(0);
        if (id.isEmpty()) {
            return MenuResponse.toast("Unknown button");
        }

        Optional<ScriptEntry> entry = library.byId(id.get());
        if (entry.isEmpty()) {
            return MenuResponse.show(ConfirmScreens.list(library.latestVersions(), page),
                    "Script not found");
        }

        boolean required = !entry.get().requiresConfirmation();
        library.setRequiresConfirmation(id.get(), required);
        return MenuResponse.show(ConfirmScreens.list(library.latestVersions(), page),
                required ? "Will ask" : "Won't ask anymore");
    }

    private MenuResponse memorySection(CallbackData data, String conversationKey) {
        return switch (data.action()) {
            case "people" -> MenuResponse.show(MemoryScreens.people(
                    memory.entities(), data.arg(1).flatMap(MenuController::parsePage).orElse(0)));
            case "person" -> personCard(data);
            case "fdel" -> deleteFact(data);
            case "pdel" -> deletePerson(data);
            case "forget" -> forget(data, conversationKey);
            case "res" -> resolve(data);
            default -> {
                log.warn("Unknown memory action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    private MenuResponse personCard(CallbackData data) {
        Optional<Long> id = data.arg(1).flatMap(MenuController::parseId);
        int page = data.arg(2).flatMap(MenuController::parsePage).orElse(0);
        Optional<Entity> entity = id.flatMap(memory::entity);
        if (entity.isEmpty()) {
            return MenuResponse.show(MemoryScreens.people(memory.entities(), 0), "Person not found");
        }
        return MenuResponse.show(MemoryScreens.person(entity.get(), memory.factsOf(entity.get().id()), page));
    }

    private MenuResponse deleteFact(CallbackData data) {
        Optional<Long> factId = data.arg(1).flatMap(MenuController::parseId);
        Optional<Long> entityId = data.arg(2).flatMap(MenuController::parseId);
        int page = data.arg(3).flatMap(MenuController::parsePage).orElse(0);
        if (factId.isEmpty() || entityId.isEmpty()) {
            return MenuResponse.toast("Unknown button");
        }
        boolean deleted = memory.deleteFact(factId.get());
        Optional<Entity> entity = memory.entity(entityId.get());
        if (entity.isEmpty()) {
            return MenuResponse.show(MemoryScreens.people(memory.entities(), 0), "Person not found");
        }
        return MenuResponse.show(
                MemoryScreens.person(entity.get(), memory.factsOf(entity.get().id()), page),
                deleted ? "Fact deleted" : "Fact already deleted");
    }

    private MenuResponse deletePerson(CallbackData data) {
        Optional<Long> id = data.arg(1).flatMap(MenuController::parseId);
        Optional<Entity> entity = id.flatMap(memory::entity);
        if (entity.isEmpty()) {
            return MenuResponse.show(MemoryScreens.people(memory.entities(), 0), "Person not found");
        }
        if (data.arg(2).filter("ok"::equals).isEmpty()) {

            return MenuResponse.show(MemoryScreens.personDeleteConfirm(
                    entity.get(), memory.factsOf(entity.get().id()).size()));
        }
        memory.deleteEntity(entity.get().id());
        return MenuResponse.show(MemoryScreens.people(memory.entities(), 0),
                "Deleted: " + entity.get().canonicalName());
    }

    private MenuResponse forget(CallbackData data, String conversationKey) {
        Optional<String> what = data.arg(1);
        if (what.isEmpty()) {
            return MenuResponse.show(MemoryScreens.forget(memory));
        }
        String description = switch (what.get()) {
            case "session" -> "the current conversation";
            case "sessions" -> "all conversations";
            case "facts" -> "all facts";
            case "all" -> "everything: people, facts and conversations";
            default -> null;
        };
        if (description == null) {
            return MenuResponse.toast("Unknown button");
        }
        if (data.arg(2).filter("ok"::equals).isEmpty()) {
            return MenuResponse.show(MemoryScreens.forgetConfirm(what.get(), description));
        }

        String toast = switch (what.get()) {
            case "session" -> "Messages forgotten: " + memory.forgetActiveSession(conversationKey);
            case "sessions" -> "Messages forgotten: " + memory.forgetAllSessions();
            case "facts" -> "Facts forgotten: " + memory.forgetAllFacts();
            default -> {
                memory.forgetEverything();
                yield "Memory cleared";
            }
        };
        return MenuResponse.show(MemoryScreens.root(memory), toast);
    }

    private MenuResponse resolve(CallbackData data) {
        Optional<String> token = data.arg(1);
        Optional<String> answer = data.arg(2);
        if (token.isEmpty() || answer.isEmpty()) {
            return MenuResponse.toast("Unknown button");
        }
        return reply(memoryActions.resolveEntity(token.get(), "same".equals(answer.get())));
    }

    private MenuResponse reminders(CallbackData data) {
        JobStore store = jobs;
        if (store == null) {
            return MenuResponse.toast("Scheduler is off");
        }
        int page = data.arg(1).flatMap(MenuController::parsePage).orElse(0);
        return switch (data.action()) {
            case "active" -> MenuResponse.show(ReminderScreens.active(store.pending(), page, zone));
            case "history" -> MenuResponse.show(ReminderScreens.history(store.history(), page, zone));
            case "view" -> data.arg(1).flatMap(MenuController::parseId).flatMap(store::byId)
                    .map(job -> MenuResponse.show(ReminderScreens.view(job,
                            data.arg(2).flatMap(MenuController::parsePage).orElse(0), zone)))
                    .orElseGet(() -> MenuResponse.show(ReminderScreens.active(store.pending(), 0, zone), "Not found"));
            case "cancel" -> {
                Optional<Long> id = data.arg(1).flatMap(MenuController::parseId);
                int back = data.arg(2).flatMap(MenuController::parsePage).orElse(0);
                boolean done = id.isPresent() && store.cancel(id.get());
                yield MenuResponse.show(ReminderScreens.active(store.pending(), back, zone),
                        done ? "Cancelled" : "Already inactive");
            }
            case "clear" -> {
                if (data.arg(1).filter("ok"::equals).isEmpty()) {
                    yield MenuResponse.show(ReminderScreens.clearConfirm(store.history().size()));
                }
                int removed = store.deleteHistory();
                yield MenuResponse.show(ReminderScreens.root(store.countPending(), 0), "Deleted: " + removed);
            }
            default -> {
                log.warn("Unknown reminder action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    private MenuResponse personaSection(CallbackData data) {
        PersonaStore store = personas;
        if (store == null) {
            return MenuResponse.toast("Personas are off");
        }
        if (data.action().equals("new")) {
            return MenuResponse.awaitInput(PersonaScreens.awaitingName(),
                    new InputRequest("persona.name", value -> {
                        try {
                            Persona created = store.create(value, "");
                            return InputOutcome.accepted("Created: " + created.name(), CallbackData.personaView(created.id()));
                        } catch (IllegalArgumentException e) {
                            return InputOutcome.rejected(e.getMessage() + ". Try again or /cancel");
                        }
                    }));
        }
        Optional<Persona> persona = data.arg(1).flatMap(MenuController::parseId).flatMap(store::byId);
        if (persona.isEmpty()) {
            return MenuResponse.show(PersonaScreens.list(store.all()), "Not found");
        }
        Persona p = persona.get();
        return switch (data.action()) {
            case "act" -> {
                store.activate(p.id());
                yield MenuResponse.show(PersonaScreens.list(store.all()), "Active: " + p.name());
            }
            case "view" -> MenuResponse.show(PersonaScreens.view(p));
            case "edit" -> MenuResponse.awaitInput(PersonaScreens.awaitingPrompt(p),
                    new InputRequest("persona.prompt." + p.id(), value -> {
                        String text = value.strip().equals("-") ? "" : value.strip();
                        store.updatePrompt(p.id(), text);
                        return InputOutcome.accepted(text.isEmpty() ? "Instruction cleared" : "Instruction saved",
                                CallbackData.personaView(p.id()));
                    }));
            case "del" -> {
                if (data.arg(2).filter("ok"::equals).isEmpty()) {
                    yield MenuResponse.show(PersonaScreens.deleteConfirm(p));
                }
                try {
                    store.delete(p.id());
                    yield MenuResponse.show(PersonaScreens.list(store.all()), "Deleted: " + p.name());
                } catch (IllegalStateException e) {
                    yield MenuResponse.show(PersonaScreens.view(p), e.getMessage());
                }
            }
            default -> {
                log.warn("Unknown persona action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    private MenuScreen notesRoot() {
        return NotesScreens.root(notes.all(NoteKind.LIST).size(), notes.all(NoteKind.NOTE).size(),
                notes.dir().toString());
    }

    private MenuResponse notes(CallbackData data) {
        NotesStore store = notes;
        if (store == null) {
            return MenuResponse.toast("Notes are off");
        }
        return switch (data.action()) {
            case "new" -> data.arg(1).isEmpty()
                    ? MenuResponse.show(NotesScreens.createChoice())
                    : awaitTitle(NoteKind.fromWire(data.arg(1).get()));
            case "list" -> MenuResponse.show(NotesScreens.list(store.all(),
                    data.arg(1).flatMap(MenuController::parsePage).orElse(0)));
            case "open" -> withNote(data, (doc, page, ip) -> MenuResponse.show(NotesScreens.open(doc, page, ip, false)));
            case "tgl" -> withNote(data, (doc, page, ip) -> {
                int item = data.arg(2).flatMap(MenuController::parseIndex).orElse(-1);
                if (item < 0 || item >= doc.items().size()) {
                    return MenuResponse.show(NotesScreens.open(doc, page, ip, false), "The list has changed");
                }
                boolean done = !doc.items().get(item).done();
                return MenuResponse.show(NotesScreens.open(store.setDone(doc.id(), item, done), page, ip, false),
                        done ? "Done" : "Unchecked");
            });
            case "rmm" -> withNote(data, (doc, page, ip) -> MenuResponse.show(NotesScreens.open(doc, page, ip, true)));
            case "rm" -> withNote(data, (doc, page, ip) -> {
                int item = data.arg(2).flatMap(MenuController::parseIndex).orElse(-1);
                if (item < 0 || item >= doc.items().size()) {
                    return MenuResponse.show(NotesScreens.open(doc, page, ip, true), "The list has changed");
                }
                String removed = doc.items().get(item).text();
                NoteDocument updated = store.removeItem(doc.id(), item);
                return MenuResponse.show(NotesScreens.open(updated, page, ip, !updated.items().isEmpty()), "Removed: " + removed);
            });
            case "add" -> withNote(data, (doc, page, ip) -> MenuResponse.awaitInput(
                    NotesScreens.awaitingText(doc, page),
                    new InputRequest("notes." + doc.id(), value -> acceptNoteText(doc.id(), page, value))));
            case "drop" -> withNote(data, (doc, page, ip) -> {
                if (data.arg(3).filter("ok"::equals).isEmpty()) {
                    return MenuResponse.show(NotesScreens.deleteConfirm(doc, page));
                }
                store.delete(doc.id());
                return MenuResponse.show(NotesScreens.list(store.all(), page), "Deleted: " + doc.title());
            });
            default -> {
                log.warn("Unknown notes action: {}", data);
                yield MenuResponse.toast("Unknown button");
            }
        };
    }

    @FunctionalInterface
    private interface NoteAction {
        MenuResponse apply(NoteDocument doc, int page, int itemPage);
    }

    private MenuResponse withNote(CallbackData data, NoteAction action) {
        boolean hasItem = data.action().equals("tgl") || data.action().equals("rm");
        int page = data.arg(hasItem ? 3 : 2).flatMap(MenuController::parsePage).orElse(0);
        int itemPage = data.arg(hasItem ? 4 : 3).flatMap(MenuController::parsePage).orElse(0);
        Optional<NoteDocument> doc = data.arg(1).flatMap(MenuController::parseId).flatMap(notes::byId);
        if (doc.isEmpty()) {
            return MenuResponse.show(NotesScreens.list(notes.all(), 0), "Not found");
        }
        return action.apply(doc.get(), page, itemPage);
    }

    private MenuResponse awaitTitle(NoteKind kind) {
        return MenuResponse.awaitInput(NotesScreens.awaitingTitle(kind),
                new InputRequest("notes.create." + kind.wire(), value -> {
                    String title = value.strip();
                    if (title.isEmpty() || title.length() > 80) {
                        return InputOutcome.rejected("Title must be 1 to 80 characters. Try again or /cancel");
                    }
                    NoteDocument doc = kind == NoteKind.LIST
                            ? notes.createList(title, List.of())
                            : notes.createNote(title, "", List.of());
                    return InputOutcome.accepted("Created: " + doc.title(), CallbackData.noteOpen(doc.id(), 0));
                }));
    }

    private InputOutcome acceptNoteText(long id, int page, String value) {
        String text = value.strip();
        if (text.isEmpty()) {
            return InputOutcome.rejected("Empty text. Try again or /cancel");
        }
        Optional<NoteDocument> doc = notes.byId(id);
        if (doc.isEmpty()) {
            return InputOutcome.accepted("Document already deleted", CallbackData.notesList(0));
        }
        if (doc.get().kind() == NoteKind.LIST) {
            notes.addItem(id, text);
            return InputOutcome.accepted("Added: " + text, CallbackData.noteOpen(id, page));
        }
        notes.append(id, text);
        return InputOutcome.accepted("Appended", CallbackData.noteOpen(id, page));
    }

    private static MenuResponse reply(AgentReply agentReply) {
        return MenuResponse.show(
                MenuScreen.of(agentReply.asPlainText(),
                        com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup.empty()));
    }

    private static Optional<Long> parseId(String raw) {
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> parsePage(String raw) {
        try {
            return Optional.of(Math.max(0, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String persist(String message) {
        try {
            settings.save();
            return message;
        } catch (ConfigException e) {
            log.error("Setting applied but not saved: {}", e.getMessage());
            return message + " (not saved to file!)";
        }
    }

    private static Optional<Integer> parseIndex(String raw) {
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private AgentState state() {
        return agentSwitch.state();
    }
}
