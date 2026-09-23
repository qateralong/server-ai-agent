package com.bebebe.agent.core;

import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.MemoryStore;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The same fact said twice in slightly different words.
 *
 * <p>Extraction runs on overlapping tails and on a rephrased conversation, so "Саша не ест мясо"
 * and "Саша не ест мяса" arrive twice as a matter of course. Since the user can now also ask for
 * something to be remembered directly, the same guard has to stand on both doors -- otherwise
 * "запомни, что я переехал" would leave two facts where the background pass had already left one.
 *
 * <p>Compared lexically, not by asking the model: a comparison per fact per run costs money on
 * background work, and a wrong "yes" from the model silently loses a real new fact. This only
 * catches near-identical wording -- a contradiction is a different problem, and it is handled by
 * superseding rather than by dropping.
 */
final class FactDedup {

    /**
     * Deliberately high: below this, wordings that share most of their words are usually two
     * different facts about the same thing ("любит чай" / "любит чай без сахара"), and dropping
     * the second one would lose the detail.
     */
    static final double DUPLICATE_AT = 0.8;

    private FactDedup() {
    }

    /**
     * @param entityIds who the new fact is about; empty means the user, whose facts are then the
     *                  neighbourhood to compare against
     * @return the fact it duplicates, if any
     */
    static Optional<Fact> duplicateOf(MemoryStore memory, String text, List<Long> entityIds) {
        Set<String> words = FactRelevance.stems(text);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        List<Fact> neighbours = entityIds == null || entityIds.isEmpty()
                ? memory.factsAboutUser()
                : memory.factsOf(entityIds.getFirst());
        for (Fact existing : neighbours) {
            if (similarity(words, FactRelevance.stems(existing.text())) >= DUPLICATE_AT) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    /** Jaccard over stems: shared words divided by all words seen in either. */
    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String word : a) {
            if (b.contains(word)) {
                shared++;
            }
        }
        return (double) shared / (a.size() + b.size() - shared);
    }
}
