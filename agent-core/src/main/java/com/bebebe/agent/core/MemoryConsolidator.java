package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogMessage;
import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    static final int BUDGET_PER_RUN = 2;

    private final MemoryStore memory;

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

        memory.markConsolidated(session.id(), lastId);

        try {
            budget.spend("fact extraction");
            List<Entity> known = memory.entities();
            LlmResponse response = llm.get().chat(new LlmRequest(
                    MemoryProtocol.extractionSystemPrompt(), List.of(),
                    MemoryProtocol.extractionUserPrompt(known, tail), 0.2,
                    MemoryProtocol.extractionSchema()));

            return apply(session, tail, known, response.text());
        } catch (LlmException e) {
            log.warn("Consolidation of session {} failed: {}", session.id(), e.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            log.error("Error consolidating session {}", session.id(), e);
            return List.of();
        }
    }

    private List<AgentReply.EntityQuestion> apply(DialogSession session,
                                                  List<DialogMessage> tail,
                                                  List<Entity> known,
                                                  String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("Model returned non-JSON during fact extraction");
            return List.of();
        }

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
            if (exact.isPresent()) {

                memory.enrichEntity(exact.get().id(), aliases, relation);
                resolved.put(name, exact.get().id());
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
            } else {
                memory.addFact(text, category, date, sourceId, entityIds);
                stored++;
            }
        }

        log.atInfo()
                .addKeyValue("event", "memory.consolidated")
                .addKeyValue("session_id", session.id())
                .addKeyValue("messages", tail.size())
                .addKeyValue("facts", stored)
                .addKeyValue("questions", asked.size())
                .log("Session {}: {} messages -> {} facts, questions: {}",
                        session.id(), tail.size(), stored, asked.size());

        List<AgentReply.EntityQuestion> questions = new ArrayList<>();
        for (PendingResolution question : asked.values()) {
            pending.put(question.token(), question);
            questions.add(new AgentReply.EntityQuestion(
                    question.token(), question.mention(), question.candidate(), question.heldFacts().size()));
        }
        return questions;
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
