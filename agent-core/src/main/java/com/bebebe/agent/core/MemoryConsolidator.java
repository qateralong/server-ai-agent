package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogMessage;
import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.llm.LlmException;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmRequest;
import com.bebebe.agent.llm.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    static final int BUDGET_PER_RUN = 2;

    private final MemoryStore memory;

    /** Session id -> the last tail we already spent a model call on. In memory: a restart may retry. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> attempted = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sessions whose summary has already been attempted, successfully or not.
     *
     * <p>Closing a session can be reached from two directions at once -- the switch-off hook does
     * it synchronously, the listener does it in the background -- and a summary that failed to
     * parse leaves nothing on disk for the second one to notice. Without this, a model having a
     * bad day would be asked to summarise the same conversation twice in a row.
     */
    private final Set<Long> summarized = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final java.util.function.Supplier<LlmProvider> llm;

    private final Map<String, PendingResolution> pending = new ConcurrentHashMap<>();

    public MemoryConsolidator(MemoryStore memory, java.util.function.Supplier<LlmProvider> llm) {
        this.memory = memory;
        this.llm = llm;
    }

    public List<AgentReply.EntityQuestion> consolidate(DialogSession session) {
        List<DialogMessage> tail = memory.unconsolidated(session);
        if (tail.stream().noneMatch(m -> m.role() == com.bebebe.agent.memory.MessageRole.USER)) {
            return List.of();
        }

        RequestBudget budget = new RequestBudget(BUDGET_PER_RUN);
        long lastId = tail.getLast().id();

        // Only one attempt per tail: a model that keeps failing must not be asked again on
        // every single message. The tail itself stays unconsolidated, so the next run -- after
        // another consolidate_every messages -- picks these messages up again.
        if (lastId <= attempted.getOrDefault(session.id(), 0L)) {
            log.debug("Session {}: tail up to {} has already been attempted", session.id(), lastId);
            return List.of();
        }
        attempted.put(session.id(), lastId);

        List<AgentReply.EntityQuestion> questions;
        try {
            budget.spend("fact extraction");
            List<Entity> known = memory.entities();
            List<Fact> shown = related(tail);
            LlmResponse response = llm.get().chat(new LlmRequest(
                    MemoryProtocol.extractionSystemPrompt(), List.of(),
                    MemoryProtocol.extractionUserPrompt(known, shown, tail), 0.2,
                    MemoryProtocol.extractionSchema()));

            JsonNode root = parse(session, response.text());
            if (root == null) {

                return List.of();
            }
            questions = apply(session, tail, known, root,
                    shown.stream().map(Fact::id).collect(java.util.stream.Collectors.toSet()));
        } catch (LlmException e) {

            log.warn("Consolidation of session {} failed: {} -- the messages are kept for the next run",
                    session.id(), e.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            log.error("Error consolidating session {} -- the messages are kept for the next run",
                    session.id(), e);
            return List.of();
        }

        // Marked only now, and only because the answer was understood. Marking before the call
        // is what silently destroyed memory: a model answering in fenced JSON burned every
        // message it was given and left nothing but a WARN behind.
        memory.markConsolidated(session.id(), lastId);
        return questions;
    }

    /**
     * @return the extraction object, or null when the model did not answer with one. Tolerant in
     *         the same way {@link AgentDecision} is: a model without strict structured output
     *         likes to wrap its JSON in ``` fences, and a bare readTree simply failed on that.
     */
    private JsonNode parse(DialogSession session, String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(AgentDecision.stripFences(json));
        } catch (Exception e) {
            root = null;
        }
        if (root != null && root.isObject()) {
            return root;
        }

        // Logged with the answer itself: without it this failure was a single unexplained line,
        // and it is why memory quietly stayed empty for days.
        log.atWarn()
                .addKeyValue("event", "memory.unparsed")
                .addKeyValue("session_id", session.id())
                .addKeyValue("answer", cut(json))
                .log("Fact extraction did not return a JSON object -- nothing extracted, "
                        + "the messages are kept for the next run: {}", cut(json));
        return null;
    }

    /** How much of what is already remembered is shown to extraction so it can mark replacements. */
    private static final int RELATED_FACTS = 40;

    private static final int RELATED_SCANNED = 500;

    /**
     * What is already remembered around this piece of conversation.
     *
     * <p>Ranked against the tail by the same relevance used to build the prompt, not just taken as
     * "the latest 40": a contradiction usually concerns something said long ago -- lives in Kazan,
     * works at such-and-such -- and recency would be exactly the wrong filter for it.
     */
    private List<Fact> related(List<DialogMessage> tail) {
        String text = tail.stream().map(DialogMessage::text).reduce("", (a, b) -> a + " " + b);
        return FactRelevance.pick(memory.allFacts(RELATED_SCANNED), text, Instant.now(), RELATED_FACTS);
    }

    private Fact duplicateOf(String text, List<Long> entityIds) {
        return FactDedup.duplicateOf(memory, text, entityIds).orElse(null);
    }

    private static String cut(String text) {
        if (text == null) {
            return "<null>";
        }
        String one = text.strip().replace('\n', ' ');
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }

    private List<AgentReply.EntityQuestion> apply(DialogSession session,
                                                  List<DialogMessage> tail,
                                                  List<Entity> known,
                                                  JsonNode root,
                                                  Set<Long> shown) {

        Map<String, Long> resolved = new LinkedHashMap<>();
        Map<String, PendingResolution> asked = new LinkedHashMap<>();
        for (JsonNode node : root.path("entities")) {
            String name = node.path("name").asText("").strip();
            if (name.isEmpty()) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(a -> aliases.add(a.asText()));
            String relation = node.path("relation").asText("");
            long matchedId = node.path("match").path("entity_id").asLong(0);
            String confidence = node.path("match").path("confidence").asText("none");

            Optional<Entity> exact = memory.findByName(name);
            Optional<Entity> near = exact.isPresent() ? exact : NameMatch.fuzzy(name, known);
            if (exact.isPresent()) {

                memory.enrichEntity(exact.get().id(), aliases, relation);
                resolved.put(name, exact.get().id());
            } else if (near.isPresent()) {

                // A typo or a different spelling of somebody already known: no point asking.
                Entity match = near.get();
                List<String> withName = new ArrayList<>(aliases);
                withName.add(name);
                memory.enrichEntity(match.id(), withName, relation);
                resolved.put(name, match.id());
                log.atInfo()
                        .addKeyValue("event", "memory.fuzzy_match")
                        .addKeyValue("entity_id", match.id())
                        .addKeyValue("mention", name)
                        .log("«{}» taken as a spelling of «{}»", name, match.canonicalName());
            } else if (matchedId > 0 && "high".equals(confidence) && memory.entity(matchedId).isPresent()) {
                List<String> withName = new ArrayList<>(aliases);
                withName.add(name);
                memory.enrichEntity(matchedId, withName, relation);
                resolved.put(name, matchedId);
            } else if (matchedId > 0 && "low".equals(confidence) && memory.entity(matchedId).isPresent()) {

                PendingResolution question = new PendingResolution(
                        newToken(), session.conversationKey(), name, relation, aliases,
                        memory.entity(matchedId).orElseThrow(), new ArrayList<>(),
                        Instant.now().plus(PendingResolution.TTL));
                asked.put(name, question);
            } else {
                Entity created = memory.addEntity(name, aliases, relation, "");
                resolved.put(name, created.id());
            }
        }

        Long sourceId = tail.getLast().id();
        int stored = 0;
        int superseded = 0;
        for (JsonNode node : root.path("facts")) {
            String text = node.path("text").asText("").strip();
            if (text.isEmpty()) {
                continue;
            }
            FactCategory category = FactCategory.fromWire(node.path("category").asText());
            LocalDate date = parseDate(node.path("date").asText(""));

            List<Long> entityIds = new ArrayList<>();
            PendingResolution holdFor = null;
            for (JsonNode ref : node.path("entities")) {
                String refName = ref.asText("").strip();
                if (resolved.containsKey(refName)) {
                    entityIds.add(resolved.get(refName));
                } else if (asked.containsKey(refName)) {
                    holdFor = asked.get(refName);
                } else if (!refName.isEmpty()) {

                    Entity entity = memory.findByName(refName)
                            .orElseGet(() -> memory.addEntity(refName, List.of(), "", ""));
                    resolved.put(refName, entity.id());
                    entityIds.add(entity.id());
                }
            }

            if (holdFor != null) {
                holdFor.heldFacts().add(new HeldFact(text, category, date, entityIds));
            } else if (duplicateOf(text, entityIds) instanceof Fact existing) {

                // Not stored twice, but not ignored either: hearing the same thing again is
                // evidence, and it is what separates a fact the user lives by from one said once.
                memory.confirmFact(existing.id());
                log.atDebug()
                        .addKeyValue("event", "memory.duplicate")
                        .addKeyValue("fact_id", existing.id())
                        .log("«{}» is already known as «{}» -- counted as a confirmation", text, existing.text());
            } else {
                Fact added = memory.addFact(text, category, date, sourceId, entityIds);
                stored++;
                superseded += supersede(node, added, shown);
            }
        }

        log.atInfo()
                .addKeyValue("event", "memory.consolidated")
                .addKeyValue("session_id", session.id())
                .addKeyValue("messages", tail.size())
                .addKeyValue("facts", stored)
                .addKeyValue("superseded", superseded)
                .addKeyValue("questions", asked.size())
                .log("Session {}: {} messages -> {} facts ({} replaced), questions: {}",
                        session.id(), tail.size(), stored, superseded, asked.size());

        List<AgentReply.EntityQuestion> questions = new ArrayList<>();
        for (PendingResolution question : asked.values()) {
            pending.put(question.token(), question);
            questions.add(new AgentReply.EntityQuestion(
                    question.token(), question.mention(), question.candidate(), question.heldFacts().size()));
        }
        return questions;
    }

    /**
     * Marks what the new fact makes obsolete.
     *
     * <p>Only ids the model was actually shown are accepted, and never the fact itself: the model
     * happily invents numbers, and a wrong one here would silently retire something true.
     *
     * @return how many facts stopped being current
     */
    private int supersede(JsonNode node, Fact added, Set<Long> shown) {
        int count = 0;
        for (JsonNode ref : node.path("replaces")) {
            long oldId = ref.asLong(0);
            if (oldId <= 0 || oldId == added.id()) {
                continue;
            }
            if (!shown.contains(oldId)) {

                // A number the model was never given. Left alone rather than trusted: it may well
                // be the id of something true, and retiring a true fact is invisible from outside.
                log.atWarn()
                        .addKeyValue("event", "memory.replaces_unknown")
                        .addKeyValue("fact_id", oldId)
                        .log("The model wants to replace fact #{}, which it was not shown -- ignored", oldId);
                continue;
            }
            if (memory.supersede(oldId, added.id(), "replaced during consolidation")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Turns a finished conversation into the few lines that outlive it.
     *
     * <p>Its own budget, like extraction: this is background work, and its failure must not take
     * attempts away from answering the user. Done once -- a session that already has a summary is
     * left alone, because closing can be reached from more than one path.
     *
     * @return the summary, or empty when there was nothing to summarise
     */
    public Optional<String> summarize(DialogSession session) {
        DialogSession fresh = memory.session(session.id()).orElse(session);
        if (fresh.hasSummary()) {
            return Optional.of(fresh.summary());
        }
        List<DialogMessage> messages = memory.messages(fresh.id());
        if (messages.stream().noneMatch(m -> m.role() == com.bebebe.agent.memory.MessageRole.USER)) {
            return Optional.empty();
        }
        if (!summarized.add(fresh.id())) {
            log.debug("Session {}: the summary has already been attempted", fresh.id());
            return Optional.empty();
        }

        RequestBudget budget = new RequestBudget(1);
        try {
            budget.spend("session summary");
            LlmResponse response = llm.get().chat(new LlmRequest(
                    MemoryProtocol.summarySystemPrompt(), List.of(),
                    MemoryProtocol.summaryUserPrompt(messages), 0.2,
                    MemoryProtocol.summarySchema()));

            JsonNode root = parse(fresh, response.text());
            if (root == null) {
                return Optional.empty();
            }
            String summary = root.path("summary").asText("").strip();
            List<String> open = new ArrayList<>();
            root.path("open").forEach(item -> {
                String line = item.asText("").strip();
                if (!line.isEmpty()) {
                    open.add(line);
                }
            });
            if (summary.isEmpty() && open.isEmpty()) {
                log.debug("Session {}: nothing worth summarising", fresh.id());
                return Optional.empty();
            }
            String text = open.isEmpty() ? summary : summary + "\nНезакрытое: " + String.join("; ", open);
            memory.saveSummary(fresh.id(), text);
            return Optional.of(text);
        } catch (LlmException e) {
            log.warn("Cannot summarise session {}: {}", fresh.id(), e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("Error summarising session {}", fresh.id(), e);
            return Optional.empty();
        }
    }

    public AgentReply resolve(String token, boolean sameAsCandidate) {
        PendingResolution question = pending.remove(token);
        if (question == null || question.isExpired(Instant.now())) {
            return AgentReply.text("Этот вопрос уже неактуален.");
        }

        Entity target;
        if (sameAsCandidate) {
            List<String> aliases = new ArrayList<>(question.aliases());
            aliases.add(question.mention());
            target = memory.enrichEntity(question.candidate().id(), aliases, question.relation());
        } else {
            target = memory.addEntity(question.mention(), question.aliases(), question.relation(), "");
        }

        for (HeldFact held : question.heldFacts()) {
            List<Long> ids = new ArrayList<>(held.entityIds());
            ids.add(target.id());
            memory.addFact(held.text(), held.category(), held.date(), null, ids);
        }

        log.info("Resolved: '{}' -> {} ({} facts)", question.mention(), target.describeForModel(),
                question.heldFacts().size());
        return AgentReply.text(sameAsCandidate
                ? "Понял, «%s» — это %s. Записал %d факт(ов)."
                        .formatted(question.mention(), target.canonicalName(), question.heldFacts().size())
                : "Понял, %s — новый человек. Записал %d факт(ов)."
                        .formatted(target.canonicalName(), question.heldFacts().size()));
    }

    public int pendingQuestions() {
        pending.values().removeIf(q -> q.isExpired(Instant.now()));
        return pending.size();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    record HeldFact(String text, FactCategory category, LocalDate date, List<Long> entityIds) {
    }

    record PendingResolution(
            String token,
            String conversationKey,
            String mention,
            String relation,
            List<String> aliases,
            Entity candidate,
            List<HeldFact> heldFacts,
            Instant expiresAt
    ) {
        static final java.time.Duration TTL = java.time.Duration.ofHours(24);

        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }
}
