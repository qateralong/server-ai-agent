package com.bebebe.agent.core;

import com.bebebe.agent.llm.OllamaProvider;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.FactSource;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Going over a pile of facts that the write path cannot see into -- and, more importantly, not
 * going too far. An automated pass that retires facts is the most dangerous thing pointed at
 * somebody's memory, so most of what is tested here is the restraint.
 */
class MemoryGardenerTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private MemoryStore memory;
    private MemoryGardener gardener;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        memory = TestMemory.inDirectory(temp);
        gardener = new MemoryGardener(memory, () -> new OllamaProvider(ollama));
    }

    @AfterEach
    void tearDown() {
        memory.close();
        ollama.close();
        stub.close();
    }

    private Fact aboutUser(String text) {
        return memory.addFact(text, FactCategory.TRAIT, null, null, List.of());
    }

    private void fillWithFourFacts() {
        aboutUser("Пользователя зовут Иван");
        aboutUser("Пользователь живёт в Казани");
        aboutUser("Пользователь работает бэкенд-разработчиком");
        aboutUser("Пользователь пользуется Arch Linux");
    }

    @Test
    void duplicatesAreMergedAndTheSurvivorInheritsTheEvidence() {
        fillWithFourFacts();
        Fact keep = aboutUser("Пользователь не ест мясо");
        Fact drop = aboutUser("Пользователь вегетарианец");
        stub.enqueue("""
                {"duplicates":[{"keep":%d,"drop":[%d]}],"outdated":[],"trivia":[]}"""
                .formatted(keep.id(), drop.id()));

        assertEquals(1, gardener.tend());

        assertFalse(memory.fact(drop.id()).orElseThrow().isCurrent());
        assertEquals(keep.id(), memory.fact(drop.id()).orElseThrow().supersededBy());
        assertEquals(2, memory.fact(keep.id()).orElseThrow().mentionCount(),
                "said twice is still said twice -- the survivor keeps the evidence");
    }

    @Test
    void aFactMadeUntrueByANewerOneStopsBeingCurrent() {
        fillWithFourFacts();
        Fact old = aboutUser("Пользователь работает в банке");
        Fact fresh = aboutUser("Пользователь работает в стартапе");
        stub.enqueue("""
                {"duplicates":[],"outdated":[{"old":%d,"new":%d}],"trivia":[]}"""
                .formatted(old.id(), fresh.id()));

        assertEquals(1, gardener.tend());

        assertFalse(memory.fact(old.id()).orElseThrow().isCurrent());
        assertTrue(memory.fact(fresh.id()).orElseThrow().isCurrent());
    }

    @Test
    void whatAHumanConfirmedIsNeverEvenShown() {
        fillWithFourFacts();
        Fact confirmed = aboutUser("Пользователь живёт в Москве");
        memory.confirmBySource(confirmed.id());
        stub.enqueue("""
                {"duplicates":[],"outdated":[],"trivia":[%d]}""".formatted(confirmed.id()));

        gardener.tend();

        assertFalse(stub.requests().getFirst().toString().contains("живёт в Москве"),
                "a fact a human vouched for is not up for discussion");
        assertTrue(memory.fact(confirmed.id()).orElseThrow().isCurrent(),
                "and an id it was not shown changes nothing even if it names one");
    }

    @Test
    void anInventedNumberIsIgnored() {
        fillWithFourFacts();
        stub.enqueue("""
                {"duplicates":[{"keep":9998,"drop":[9999]}],"outdated":[],"trivia":[4242]}""");

        assertEquals(0, gardener.tend());
        assertEquals(4, memory.countFacts());
    }

    @Test
    void oneRunRetiresAtMostAHandful() {
        for (int i = 0; i < 30; i++) {
            aboutUser("Пользователь помнит факт номер " + i);
        }
        List<Fact> all = memory.factsAboutUser();
        Fact keep = all.getFirst();
        String drop = all.stream().skip(1).map(f -> String.valueOf(f.id()))
                .reduce((a, b) -> a + "," + b).orElseThrow();
        stub.enqueue("""
                {"duplicates":[{"keep":%d,"drop":[%s]}],"outdated":[],"trivia":[]}"""
                .formatted(keep.id(), drop));

        int changed = gardener.tend();

        assertEquals(MemoryGardener.MAX_CHANGES, changed,
                "a model having a bad day gets to be wrong about a few facts, not about memory");
        assertEquals(30 - MemoryGardener.MAX_CHANGES, memory.countFacts());
    }

    @Test
    void noiseIsRetiredEvenMoreCautiouslyThanTheRest() {
        for (int i = 0; i < 10; i++) {
            aboutUser("Пользователь помнит факт номер " + i);
        }
        String ids = memory.factsAboutUser().stream().map(f -> String.valueOf(f.id()))
                .reduce((a, b) -> a + "," + b).orElseThrow();
        stub.enqueue("""
                {"duplicates":[],"outdated":[],"trivia":[%s]}""".formatted(ids));

        assertEquals(MemoryGardener.MAX_TRIVIA, gardener.tend(),
                "«это мусор» is the least certain verdict, so it gets the tightest cap");
    }

    @Test
    void nothingToDoIsTheNormalAnswerAndCostsNothingAfterTheCall() {
        fillWithFourFacts();
        stub.enqueue("""
                {"duplicates":[],"outdated":[],"trivia":[]}""");

        assertEquals(0, gardener.tend());
        assertEquals(4, memory.countFacts());
    }

    @Test
    void aSmallPileIsNotWorthAModelCall() {
        aboutUser("Пользователя зовут Иван");
        aboutUser("Пользователь живёт в Казани");

        assertEquals(0, gardener.tend());
        assertEquals(0, stub.callCount(), "below a handful of facts there is nothing to notice");
    }

    @Test
    void anUnreadableAnswerTouchesNothing() {
        fillWithFourFacts();
        stub.enqueue("извините, не понял");

        assertEquals(0, gardener.tend());
        assertEquals(4, memory.countFacts());
    }

    /** Один проход — одна группа, и на следующий раз берётся следующая. */
    @Test
    void groupsAreTakenInTurn() {
        fillWithFourFacts();
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");
        for (int i = 0; i < 4; i++) {
            memory.addFact("Саша помнит факт " + i, FactCategory.TRAIT, null, null, List.of(sasha.id()));
        }
        stub.enqueue("""
                {"duplicates":[],"outdated":[],"trivia":[]}""");
        stub.enqueue("""
                {"duplicates":[],"outdated":[],"trivia":[]}""");

        assertEquals("пользователе", gardener.nextGroup().orElseThrow());
        gardener.tend();
        assertEquals("Саша", gardener.nextGroup().orElseThrow());
        gardener.tend();
        assertEquals("пользователе", gardener.nextGroup().orElseThrow(), "and round again");
    }

    @Test
    void factsGoToTheModelWithTheirNumbers() {
        fillWithFourFacts();
        stub.enqueue("""
                {"duplicates":[],"outdated":[],"trivia":[]}""");

        gardener.tend();

        String asked = stub.requests().getFirst().toString();
        assertTrue(asked.contains("#" + memory.factsAboutUser().getFirst().id()),
                "without numbers there is nothing for the answer to point at: " + asked);
    }

    @Test
    void confirmedFactsDoNotCountTowardsThePileBeingWorthLookingAt() {
        for (int i = 0; i < 5; i++) {
            Fact fact = aboutUser("Пользователь помнит факт номер " + i);
            memory.confirmBySource(fact.id());
        }
        aboutUser("Пользователь помнит что-то ещё");

        assertEquals(0, gardener.tend());
        assertEquals(0, stub.callCount(),
                "one unchecked fact among five confirmed ones is not a pile to go over");
        assertEquals(FactSource.CONFIRMED, memory.factsAboutUser().getLast().source());
    }
}
