package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.tools.memory.MemoryAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The memory tools, wired to the real store.
 *
 * <p>Lives here rather than in {@code tools} because everything that makes an answer good is
 * here: the ranking, the near-duplicate guard, the fuzzy matching of a name against people already
 * known. The tools get a port; this is the part that knows what a fact is worth.
 */
final class CoreMemoryAccess implements MemoryAccess {

    private static final Logger log = LoggerFactory.getLogger(CoreMemoryAccess.class);

    private final MemoryStore memory;
    private final MemoryRecall lookup;
    private final Clock clock;

    CoreMemoryAccess(MemoryStore memory, MemoryRecall recall, Clock clock) {
        this.memory = memory;
        this.lookup = recall;
        this.clock = clock;
    }

    @Override
    public Recall recall(String query, String about) {
        MemoryRecall.Selection selection = lookup.search(
                query == null ? "" : query, about == null ? "" : about, clock.instant());

        List<String> facts = new ArrayList<>();
        selection.factsByEntity().forEach((entity, list) -> {
            if (!list.isEmpty()) {
                facts.add(entity.describeForModel());
                list.forEach(fact -> facts.add("  " + fact.describeForModel()));
            }
        });
        selection.recalled().forEach(fact -> facts.add(fact.describeForModel()));

        List<String> episodes = selection.episodes().stream()
                .map(session -> "• " + (session.endedAt() == null ? "" : session.endedAt().toString().substring(0, 10) + ": ")
                        + session.summary().replace("\n", " / "))
                .toList();

        boolean nothing = facts.isEmpty() && episodes.isEmpty();
        return new Recall(facts, episodes, nothing ? lookup.knownPeople() : List.of());
    }

    @Override
    public Outcome remember(String text, String category, List<String> about, long replaces) {
        List<Long> entityIds = new ArrayList<>();
        List<String> people = new ArrayList<>();
        for (String name : about == null ? List.<String>of() : about) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Entity entity = lookup.entityByName(name)
                    .orElseGet(() -> memory.addEntity(name.strip(), List.of(), "", ""));
            entityIds.add(entity.id());
            people.add(entity.canonicalName());
        }

        Optional<Fact> duplicate = FactDedup.duplicateOf(memory, text, entityIds);
        if (duplicate.isPresent() && replaces <= 0) {

            // Already known in almost these words. Saying so is better than either writing it
            // twice or answering "записал" about something that changed nothing.
            memory.confirmFact(duplicate.get().id());
            return Outcome.ok("Already remembered, in almost the same words: «"
                    + duplicate.get().text() + "» (#" + duplicate.get().id() + "). Counted as a "
                    + "confirmation, nothing new written. Tell the user you already knew this.");
        }

        Fact stored = memory.addFact(text, FactCategory.fromWire(category), null, null, entityIds);

        String replaced = "";
        if (replaces > 0) {
            Optional<Fact> old = memory.fact(replaces);
            if (old.isEmpty()) {
                replaced = " The fact #" + replaces + " you wanted to replace does not exist -- "
                        + "the new one is stored on its own.";
            } else if (memory.supersede(replaces, stored.id(), "corrected by the user")) {
                replaced = " The old fact «" + old.get().text() + "» (#" + replaces
                        + ") is no longer current.";
            } else {
                replaced = " The fact #" + replaces + " had already stopped being current.";
            }
        }

        log.atInfo()
                .addKeyValue("event", "memory.remembered")
                .addKeyValue("fact_id", stored.id())
                .addKeyValue("entities", people.toString())
                .addKeyValue("replaces", replaces)
                .log("Remembered on request: {}", stored.text());

        return Outcome.ok("Remembered: «" + stored.text() + "» (#" + stored.id() + ")"
                + (people.isEmpty() ? ", about the user" : ", about " + String.join(", ", people))
                + "." + replaced + " Confirm it to the user briefly, without the numbers.");
    }

    @Override
    public Outcome forget(long factId, String reason) {
        Optional<Fact> fact = memory.fact(factId);
        if (fact.isEmpty()) {
            return Outcome.failed("There is no fact #" + factId + ". Call recall to see what is "
                    + "actually remembered, and do not claim to have forgotten anything.");
        }
        if (!fact.get().isCurrent()) {
            return Outcome.ok("The fact «" + fact.get().text() + "» had already stopped being "
                    + "current -- nothing to do.");
        }
        memory.supersede(factId, null, reason == null || reason.isBlank() ? "retracted by the user" : reason);
        return Outcome.ok("No longer believed: «" + fact.get().text() + "». It is kept in the "
                + "history but will not be used again. Confirm briefly.");
    }
}
