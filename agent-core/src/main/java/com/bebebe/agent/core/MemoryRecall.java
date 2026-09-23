package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What of everything remembered goes in front of the model for <i>this</i> message.
 *
 * <p>Split out of {@link AgentCore} so that the thing every memory improvement is judged by can be
 * run on its own: {@code MemoryEvalTest} feeds it a fixed corpus and a list of questions whose
 * answers are known, and reports how often the right fact actually reaches the prompt. Before
 * that the selection could only be exercised through a full agent turn, so "recall got better"
 * was an opinion.
 *
 * <p>Four things are offered, and they are separate on purpose -- the model is told how each one
 * was found, because "you mentioned this person" and "one of your words matched" deserve
 * different amounts of trust:
 *
 * <ol>
 *   <li>people named in the message, with their facts;
 *   <li>facts about the user, and procedures they asked to keep;
 *   <li>a keyword sweep over everything else -- offered as a lead, not as the subject;
 *   <li>summaries of recent finished conversations.
 * </ol>
 */
final class MemoryRecall {

    /**
     * How many facts of one kind may reach the prompt. Past this the ranking decides; the rest
     * stays in the database and is still visible in 🧠 Memory -- it is dropped from one prompt,
     * not forgotten.
     */
    static final int FACTS_PER_ENTITY = 8;

    static final int FACTS_ABOUT_USER = 10;

    static final int PROCEDURES = 12;

    /** How many facts keyword recall may add on top of the blocks above. */
    static final int RECALL_LIMIT = 6;

    /** How far back recall looks. Beyond this the lexical scan stops paying for itself. */
    static final int RECALL_SCANNED = 500;

    /** Recent finished conversations offered as episodic context. Kept small: they are prose. */
    static final int EPISODES = 3;

    /** The explicit search behind the recall tool digs deeper than the automatic sweep. */
    static final int SEARCH_SCANNED = 2000;

    static final int SEARCH_LIMIT = 12;

    private final MemoryStore memory;
    private final EntityResolver entities;

    MemoryRecall(MemoryStore memory) {
        this.memory = memory;
        this.entities = new EntityResolver(memory);
    }

    /**
     * @param offered ids of every fact shown, in the order shown. The model quotes numbers back in
     *                {@code used_facts}, and only these are accepted -- otherwise any number the
     *                model invented would count as a use.
     */
    record Selection(List<Entity> mentioned,
                     Map<Entity, List<Fact>> factsByEntity,
                     List<Fact> aboutUser,
                     List<Fact> procedures,
                     List<Fact> recalled,
                     List<DialogSession> episodes,
                     List<Long> offered) {

        String block() {
            return MemoryProtocol.contextBlock(mentioned, factsByEntity, aboutUser, procedures,
                    recalled, episodes);
        }

        boolean isEmpty() {
            return offered.isEmpty() && episodes.isEmpty();
        }
    }

    Selection select(String message, Instant now) {
        List<Entity> mentioned = entities.resolve(message);
        Map<Entity, List<Fact>> byEntity = new LinkedHashMap<>();
        for (Entity entity : mentioned) {
            byEntity.put(entity, FactRelevance.pick(
                    memory.factsOf(entity.id()), message, now, FACTS_PER_ENTITY));
        }
        List<Fact> aboutUser = FactRelevance.pick(
                memory.factsAboutUser(), message, now, FACTS_ABOUT_USER);
        List<Fact> procedures = FactRelevance.pick(
                memory.factsByCategory(FactCategory.PROCEDURE), message, now, PROCEDURES);

        // Recall by keyword over everything else. Without it a fact reached the prompt only when
        // the name of the person it is about literally appeared in the message, so «кто из
        // знакомых вегетарианец?» found nothing although the fact was stored.
        Set<Long> already = new HashSet<>();
        byEntity.values().forEach(list -> list.forEach(f -> already.add(f.id())));
        aboutUser.forEach(f -> already.add(f.id()));
        procedures.forEach(f -> already.add(f.id()));

        List<Fact> recalled = FactRelevance.matching(
                        memory.allFacts(RECALL_SCANNED), message, now, RECALL_LIMIT + already.size())
                .stream()
                .filter(f -> !already.contains(f.id()))
                .limit(RECALL_LIMIT)
                .toList();

        return new Selection(mentioned, byEntity, aboutUser, procedures, recalled,
                memory.recentSummaries(EPISODES), ids(byEntity.values(), aboutUser, procedures, recalled));
    }

    /**
     * The explicit search behind the {@code recall} tool: the model's own query instead of the
     * words of the message, the whole catalogue instead of the recent tail, and no cut-off by
     * kind of fact.
     */
    Selection search(String query, String about, Instant now) {
        String text = (query + " " + about).strip();
        Map<Entity, List<Fact>> byEntity = new LinkedHashMap<>();
        List<Entity> named = new ArrayList<>();
        if (!about.isBlank()) {
            entityByName(about).ifPresent(entity -> {
                named.add(entity);
                byEntity.put(entity, FactRelevance.pick(
                        memory.factsOf(entity.id()), query, now, SEARCH_LIMIT));
            });
        }
        if (named.isEmpty()) {
            for (Entity entity : entities.resolve(text)) {
                named.add(entity);
                byEntity.put(entity, FactRelevance.pick(
                        memory.factsOf(entity.id()), query, now, FACTS_PER_ENTITY));
            }
        }

        Set<Long> already = new HashSet<>();
        byEntity.values().forEach(list -> list.forEach(f -> already.add(f.id())));
        List<Fact> matched = FactRelevance.matching(
                        memory.allFacts(SEARCH_SCANNED), text, now, SEARCH_LIMIT + already.size())
                .stream()
                .filter(f -> !already.contains(f.id()))
                .limit(SEARCH_LIMIT)
                .toList();

        return new Selection(named, byEntity, List.of(), List.of(), matched,
                episodesMatching(text), ids(byEntity.values(), List.of(), List.of(), matched));
    }

    /** Past conversations whose summary shares a word with the query, newest first. */
    List<DialogSession> episodesMatching(String query) {
        Set<String> stems = FactRelevance.stems(query);
        if (stems.isEmpty()) {
            return List.of();
        }
        List<DialogSession> hits = new ArrayList<>();
        for (DialogSession session : memory.recentSummaries(SEARCH_LIMIT * 3)) {
            Set<String> summary = FactRelevance.stems(session.summary());
            if (stems.stream().anyMatch(summary::contains)) {
                hits.add(session);
            }
            if (hits.size() >= EPISODES) {
                break;
            }
        }
        return hits;
    }

    Optional<Entity> entityByName(String name) {
        Optional<Entity> exact = memory.findByName(name);
        return exact.isPresent() ? exact : NameMatch.fuzzy(name, memory.entities());
    }

    List<String> knownPeople() {
        return memory.entities().stream().map(Entity::canonicalName).limit(20).toList();
    }

    private static List<Long> ids(Iterable<List<Fact>> byEntity, List<Fact> aboutUser,
                                  List<Fact> procedures, List<Fact> recalled) {
        Set<Long> ids = new LinkedHashSet<>();
        byEntity.forEach(list -> list.forEach(f -> ids.add(f.id())));
        aboutUser.forEach(f -> ids.add(f.id()));
        procedures.forEach(f -> ids.add(f.id()));
        recalled.forEach(f -> ids.add(f.id()));
        return List.copyOf(ids);
    }
}
