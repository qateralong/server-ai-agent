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
 * <p>The ranking is deliberately lexical and cheap -- word overlap with the message plus a
 * recency bonus, no model call and no embeddings. It decides <b>order and cut-off</b>, not
 * truth: a fact that scores nothing is not wrong, it is merely last. Real semantic recall
 * ("that tall guy from the meeting") needs vectors and is still in the backlog.
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

    static double score(Fact fact, Set<String> query, Instant now) {
        double score = overlap(stems(fact.text()), query) * 3.0;

        // A procedure is an instruction the user asked to keep; it stays useful long after the
        // conversation that produced it, so it is never ranked down by age.
        if (fact.category() == FactCategory.PROCEDURE) {
            score += 2.0;
        }

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
