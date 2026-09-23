package com.bebebe.agent.core;

import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which facts are worth putting in front of the model for <i>this</i> message.
 *
 * <p>Everything known about everyone mentioned used to go in whole. That is fine with a dozen
 * facts and wasteful with a few hundred: the prompt grows with the size of memory rather than
 * with the question, and the useful line ends up buried among the irrelevant ones.
 *
 * <p>The ranking is deliberately lexical and cheap -- word overlap with the message, a recency
 * bonus and what the fact has earned by being confirmed and used, no model call and no
 * embeddings. It decides <b>order and cut-off</b>, not truth: a fact that scores nothing is not
 * wrong, it is merely last. Real semantic recall ("that tall guy from the meeting") needs vectors
 * and is still in the backlog.
 *
 * <p>Every signal here is a <b>bonus and never a penalty</b>, and that is deliberate. A fact is
 * only ever marked used when it was shown to the model in the first place, so docking points for
 * "never used" would push a fact that was never offered further out of sight on every turn, and
 * it could never earn its way back. Useful facts rise; the rest keep the place the words gave
 * them.
 */
final class FactRelevance {

    /** Short words carry no signal and match everything. */
    private static final int MIN_WORD = 4;

    /**
     * Crude morphology, as in the script catalogue: a prefix stands for a stem. Short words get
     * their last letter dropped instead -- cutting "мясо" and "мяса" to four characters leaves
     * them different, which is exactly the case this has to catch.
     */
    private static final int STEM = 4;

    private static final int SHORT_WORD = 5;

    private static final Pattern WORDS = Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Anything newer than this is fresh enough to be worth showing on its own. */
    private static final Duration RECENT = Duration.ofDays(14);

    /** Per repeated confirmation by extraction, and how many of them still count. */
    private static final double PER_CONFIRMATION = 0.5;
    private static final int CONFIRMATIONS_COUNTED = 3;

    /** Per time the model said it leaned on the fact, and how many of them still count. */
    private static final double PER_USE = 0.5;
    private static final int USES_COUNTED = 4;

    /** The user asked for this to be remembered, or looked at it and said it was right. */
    private static final double STATED_BY_USER = 1.0;
    private static final double CONFIRMED_BY_USER = 2.0;

    private FactRelevance() {
    }

    /**
     * @param facts   what is known
     * @param message the user's message, as the source of keywords
     * @param limit   how many to keep
     * @return the most relevant first, at most {@code limit}
     */
    static List<Fact> pick(List<Fact> facts, String message, Instant now, int limit) {
        if (facts.size() <= limit) {

            return facts;
        }
        Set<String> query = stems(message);
        List<Fact> ranked = new ArrayList<>(facts);
        ranked.sort(Comparator.comparingDouble((Fact f) -> -score(f, query, now))
                .thenComparing(Fact::createdAt, Comparator.reverseOrder()));
        return List.copyOf(ranked.subList(0, limit));
    }

    /**
     * Facts that share at least one word with the message, best first.
     *
     * <p>Unlike {@link #pick} this never falls back to recency: a fact with no word in common is
     * not "least relevant", it is simply not an answer to this message, and padding the prompt
     * with recent unrelated facts is how a prompt grows without getting better.
     */
    static List<Fact> matching(List<Fact> facts, String message, Instant now, int limit) {
        Set<String> query = stems(message);
        if (query.isEmpty() || facts.isEmpty()) {
            return List.of();
        }
        List<Fact> hits = new ArrayList<>();
        for (Fact fact : facts) {
            if (overlap(stems(fact.searchText()), query) > 0) {
                hits.add(fact);
            }
        }
        hits.sort(Comparator.comparingDouble((Fact f) -> -score(f, query, now))
                .thenComparing(Fact::createdAt, Comparator.reverseOrder()));
        return List.copyOf(hits.subList(0, Math.min(limit, hits.size())));
    }

    static double score(Fact fact, Set<String> query, Instant now) {

        // searchText(), not text(): the keywords written down with the fact are exactly there to
        // be matched against a question phrased in other words than the fact itself.
        double score = overlap(stems(fact.searchText()), query) * 3.0;

        // A procedure is an instruction the user asked to keep; it stays useful long after the
        // conversation that produced it, so it is never ranked down by age.
        if (fact.category() == FactCategory.PROCEDURE) {
            score += 2.0;
        }

        // Heard again, and again: extraction ran into the same thing on a later tail. Cheap,
        // honest evidence that this is part of the user's life rather than something said once.
        score += Math.min(fact.mentionCount() - 1, CONFIRMATIONS_COUNTED) * PER_CONFIRMATION;

        // Actually leaned on when answering. The only signal that comes from the memory having
        // worked rather than from guessing which words look relevant.
        score += Math.min(fact.usedCount(), USES_COUNTED) * PER_USE;

        // What the user said outright, and what they have since confirmed, outranks what the
        // model decided on its own was worth keeping.
        score += switch (fact.source()) {
            case CONFIRMED -> CONFIRMED_BY_USER;
            case STATED -> STATED_BY_USER;
            case EXTRACTED -> 0.0;
        };

        Instant created = fact.createdAt();
        if (created != null) {
            Duration age = Duration.between(created, now);
            if (!age.isNegative() && age.compareTo(RECENT) < 0) {

                score += 1.0 - (double) age.toHours() / RECENT.toHours();
            }
        }
        return score;
    }

    /** How many of the query's stems the fact contains, relative to the query's size. */
    private static double overlap(Set<String> factStems, Set<String> query) {
        if (query.isEmpty() || factStems.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String stem : query) {
            if (factStems.contains(stem)) {
                hits++;
            }
        }
        return (double) hits / query.size();
    }

    static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String word : WORDS.split(text.toLowerCase(Locale.ROOT))) {
            if (word.length() >= MIN_WORD) {
                out.add(word.length() <= SHORT_WORD
                        ? word.substring(0, word.length() - 1)
                        : word.substring(0, STEM));
            }
        }
        return out;
    }
}
