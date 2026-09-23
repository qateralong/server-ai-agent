package com.bebebe.agent.tools.memory;

import java.util.List;

/**
 * Long-term memory as the tools see it.
 *
 * <p>A narrow port rather than the store itself, for the same reason the reminder tools take a
 * {@code Sink} instead of the scheduler: the ranking, the wording of a fact and the resolution of
 * a name against known people all live in the core, and {@code tools} has no business depending
 * on the database to reach them.
 *
 * <p>The point of having memory here at all is that recall stops being a single guess made before
 * the model has said anything. Facts are still pre-loaded by keyword into the prompt -- that is
 * free and covers most of it -- but when the guess misses, the model can now ask, in its own
 * words, instead of quietly answering as if it never knew.
 */
public interface MemoryAccess {

    /**
     * @param query what to look for, in the user's own words
     * @param about a person's name to narrow it down, or empty
     */
    Recall recall(String query, String about);

    /**
     * @param category one of the wire names of a fact category; anything unknown becomes "event"
     * @param about    names of the people the fact is about; empty means it is about the user
     * @param replaces the fact this one supersedes, or 0
     * @param keywords other words a later question about this might use; may be empty
     */
    Outcome remember(String text, String category, List<String> about, long replaces, List<String> keywords);

    Outcome forget(long factId, String reason);

    /**
     * @param facts    matching facts, already ranked and rendered for the model
     * @param episodes summaries of past conversations that match
     * @param people   who the agent knows -- shown when nothing matched, so that the answer can be
     *                 "нет, про это не помню" instead of a silence the model fills in itself
     */
    record Recall(List<String> facts, List<String> episodes, List<String> people) {

        public Recall {
            facts = facts == null ? List.of() : List.copyOf(facts);
            episodes = episodes == null ? List.of() : List.copyOf(episodes);
            people = people == null ? List.of() : List.copyOf(people);
        }

        public boolean isEmpty() {
            return facts.isEmpty() && episodes.isEmpty();
        }
    }

    record Outcome(boolean ok, String message) {

        public static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        public static Outcome failed(String message) {
            return new Outcome(false, message);
        }
    }
}
