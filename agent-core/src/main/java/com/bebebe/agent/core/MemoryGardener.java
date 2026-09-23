package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactSource;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.llm.LlmException;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmRequest;
import com.bebebe.agent.llm.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Going over memory once in a while, the way nothing on the write path can.
 *
 * <p>Every guard that runs when a fact is stored looks at one fact against one neighbourhood:
 * near-duplicate detection compares wording, and replacement only happens when the model
 * noticed the contradiction while reading the conversation. Over months that leaves three ways
 * of saying the same thing, a fact quietly contradicting one written half a year earlier in
 * completely different words, and the odd line extraction should never have kept. Seeing any of
 * it requires looking at a whole pile at once, which is what this does.
 *
 * <p>One group of facts per run, one model call, at most a handful of changes. That pace is the
 * point: a fact left alone for another day costs one line in a prompt, and an automated pass with
 * an appetite is the most dangerous thing that could be pointed at somebody's memory.
 */
final class MemoryGardener {

    private static final Logger log = LoggerFactory.getLogger(MemoryGardener.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Below this a pile is not a pile and there is nothing to notice. */
    static final int MIN_FACTS = 4;

    /** How much goes to the model at once. */
    static final int BATCH = 40;

    /**
     * How many facts one run may retire. A model having a bad day gets to be wrong about a few
     * things, not about somebody's memory; everything else waits for tomorrow.
     */
    static final int MAX_CHANGES = 10;

    /** And of those, how many may go merely for being noise -- the least certain verdict. */
    static final int MAX_TRIVIA = 3;

    private final MemoryStore memory;
    private final Supplier<LlmProvider> llm;

    /** Where the rotation is. In memory: a restart simply starts the round again. */
    private int cursor;

    MemoryGardener(MemoryStore memory, Supplier<LlmProvider> llm) {
        this.memory = memory;
        this.llm = llm;
    }

    /**
     * Tends one group of facts: the user's own, or one person's, taken in turn.
     *
     * @return how many facts stopped being current
     */
    int tend() {
        List<Group> groups = groups();
        if (groups.isEmpty()) {
            log.debug("Nothing to tend yet");
            return 0;
        }
        Group group = groups.get(Math.floorMod(cursor++, groups.size()));

        // Confirmed facts are not up for discussion: a human looked at them and said they were
        // right, which outranks anything this pass can work out on its own.
        List<Fact> facts = group.facts().stream()
                .filter(fact -> fact.source() != FactSource.CONFIRMED)
                .limit(BATCH)
                .toList();
        if (facts.size() < MIN_FACTS) {
            return 0;
        }

        RequestBudget budget = new RequestBudget(1);
        JsonNode root;
        try {
            budget.spend("memory gardening");
            LlmResponse response = llm.get().chat(new LlmRequest(
                    MemoryProtocol.gardeningSystemPrompt(), List.of(),
                    MemoryProtocol.gardeningUserPrompt(group.about(), facts), 0.1,
                    MemoryProtocol.gardeningSchema()));
            root = parse(response.text());
        } catch (LlmException e) {
            log.warn("Gardening of «{}» failed: {}", group.about(), e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            log.error("Error while gardening «{}»", group.about(), e);
            return 0;
        }
        if (root == null) {
            return 0;
        }
        return apply(group, facts, root);
    }

    private int apply(Group group, List<Fact> shown, JsonNode root) {
        Set<Long> allowed = new HashSet<>();
        shown.forEach(fact -> allowed.add(fact.id()));

        int changed = 0;
        int trivia = 0;

        for (JsonNode node : root.path("duplicates")) {
            long keep = node.path("keep").asLong(0);
            if (!allowed.contains(keep)) {
                continue;
            }
            for (JsonNode dropped : node.path("drop")) {
                long drop = dropped.asLong(0);
                if (drop == keep || !allowed.contains(drop) || changed >= MAX_CHANGES) {
                    continue;
                }
                if (memory.supersede(drop, keep, "gardening: same as #" + keep)) {

                    // Said twice is still said twice: the survivor inherits the evidence instead
                    // of losing it along with the wording that is going away.
                    memory.confirmFact(keep);
                    changed++;
                }
            }
        }

        for (JsonNode node : root.path("outdated")) {
            long old = node.path("old").asLong(0);
            long fresh = node.path("new").asLong(0);
            if (old == fresh || !allowed.contains(old) || !allowed.contains(fresh) || changed >= MAX_CHANGES) {
                continue;
            }
            if (memory.supersede(old, fresh, "gardening: replaced by #" + fresh)) {
                changed++;
            }
        }

        for (JsonNode node : root.path("trivia")) {
            long id = node.asLong(0);
            if (!allowed.contains(id) || changed >= MAX_CHANGES || trivia >= MAX_TRIVIA) {
                continue;
            }
            if (memory.supersede(id, null, "gardening: trivia")) {
                changed++;
                trivia++;
            }
        }

        log.atInfo()
                .addKeyValue("event", "memory.gardened")
                .addKeyValue("about", group.about())
                .addKeyValue("looked_at", shown.size())
                .addKeyValue("changed", changed)
                .addKeyValue("trivia", trivia)
                .log("Gardened «{}»: {} of {} facts are no longer current",
                        group.about(), changed, shown.size());
        return changed;
    }

    private JsonNode parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(AgentDecision.stripFences(json));
        } catch (Exception e) {
            root = null;
        }
        if (root != null && root.isObject()) {
            return root;
        }
        log.atWarn()
                .addKeyValue("event", "memory.gardening_unparsed")
                .addKeyValue("answer", json == null ? "" : json.strip())
                .log("Gardening did not return a JSON object -- nothing touched");
        return null;
    }

    /** The user's own facts, then one group per known person. */
    private List<Group> groups() {
        List<Group> groups = new ArrayList<>();
        List<Fact> mine = memory.factsAboutUser();
        if (mine.size() >= MIN_FACTS) {
            groups.add(new Group("пользователе", mine));
        }
        for (Entity entity : memory.entities()) {
            List<Fact> facts = memory.factsOf(entity.id());
            if (facts.size() >= MIN_FACTS) {
                groups.add(new Group(entity.canonicalName(), facts));
            }
        }
        return groups;
    }

    /** For tests: which group the next run will take. */
    Optional<String> nextGroup() {
        List<Group> groups = groups();
        return groups.isEmpty()
                ? Optional.empty()
                : Optional.of(groups.get(Math.floorMod(cursor, groups.size())).about());
    }

    private record Group(String about, List<Fact> facts) {
    }
}
