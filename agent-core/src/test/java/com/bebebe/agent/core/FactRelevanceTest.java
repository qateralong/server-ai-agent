package com.bebebe.agent.core;

import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactRelevanceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private static Fact fact(String text, FactCategory category, Duration age) {
        return new Fact(0, text, category, null, null, List.of(), NOW.minus(age));
    }

    private static List<String> texts(List<Fact> facts) {
        return facts.stream().map(Fact::text).toList();
    }

    @Test
    void aShortListIsPassedThroughUntouched() {
        List<Fact> few = List.of(fact("любит чай", FactCategory.PREFERENCE, Duration.ofDays(400)));

        assertSame(few, FactRelevance.pick(few, "что угодно", NOW, 8),
                "no work and no copying while everything fits");
    }

    @Test
    void factsSharingWordsWithTheMessageComeFirst() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            facts.add(fact("посторонний факт номер " + i, FactCategory.TRAIT, Duration.ofDays(300)));
        }
        facts.add(fact("Саша не ест мясо", FactCategory.PREFERENCE, Duration.ofDays(300)));

        List<Fact> picked = FactRelevance.pick(facts, "что приготовить на ужин, чтобы было без мяса?", NOW, 3);

        assertTrue(texts(picked).contains("Саша не ест мясо"),
                "the only fact that shares a word with the question must survive the cut: " + texts(picked));
    }

    @Test
    void freshFactsBeatEquallyIrrelevantOldOnes() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            facts.add(fact("старое " + i, FactCategory.TRAIT, Duration.ofDays(200)));
        }
        Fact yesterday = fact("вчерашнее", FactCategory.TRAIT, Duration.ofDays(1));
        facts.add(yesterday);

        List<Fact> picked = FactRelevance.pick(facts, "совершенно другая тема", NOW, 2);

        assertEquals("вчерашнее", picked.getFirst().text(),
                "with nothing to match on, recency is the only honest tie-break");
    }

    @Test
    void proceduresAreRankedAboveOrdinaryFactsOfTheSameAge() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            facts.add(fact("обычный факт " + i, FactCategory.TRAIT, Duration.ofDays(100)));
        }
        facts.add(fact("перед стримом включить OBS и свет", FactCategory.PROCEDURE, Duration.ofDays(100)));

        List<Fact> picked = FactRelevance.pick(facts, "ничего общего", NOW, 1);

        assertEquals(FactCategory.PROCEDURE, picked.getFirst().category(),
                "an instruction the user asked to keep does not go stale like an observation");
    }

    @Test
    void shortWordsDoNotMatchEverything() {

        assertTrue(FactRelevance.stems("я и он не да").isEmpty(),
                "two-letter words would match any fact at all");
    }

    @Test
    void theCutIsNeverLargerThanAsked() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            facts.add(fact("факт " + i, FactCategory.TRAIT, Duration.ofDays(i)));
        }

        assertEquals(8, FactRelevance.pick(facts, "вопрос", NOW, 8).size());
    }
}
