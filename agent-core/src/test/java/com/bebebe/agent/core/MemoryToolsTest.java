package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import com.bebebe.agent.tools.memory.ForgetTool;
import com.bebebe.agent.tools.memory.RecallTool;
import com.bebebe.agent.tools.memory.RememberTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory the agent can reach for, write to and take back -- instead of memory that is handed to it
 * once, by keyword, before it has said anything.
 */
class MemoryToolsTest {

    @TempDir
    Path temp;

    private MemoryStore memory;
    private CoreMemoryAccess access;
    private MemoryRecall recall;

    /** Nothing here may call the model: recall and remember are supposed to be free. */
    private final ToolContext context = new ToolContext((system, user, schema) -> {
        throw new AssertionError("A memory tool must not spend a model call");
    }, "вопрос");

    @BeforeEach
    void setUp() {
        memory = TestMemory.inDirectory(temp);
        recall = new MemoryRecall(memory);
        access = new CoreMemoryAccess(memory, recall, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private ToolResult recall(String query, String about) {
        return new RecallTool(access).execute(Map.of("query", query, "about", about), context);
    }

    private ToolResult remember(Map<String, Object> arguments) {
        return new RememberTool(access).execute(arguments, context);
    }

    private ToolResult forget(Object factId) {
        return new ForgetTool(access).execute(Map.of("fact_id", factId, "reason", "устарело"), context);
    }

    @Test
    void recallFindsWhatTheKeywordPreloadWouldHaveMissed() {
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");
        memory.addFact("Саша не ест мясо", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));

        // The message the user actually typed shares no word with the fact -- this is exactly the
        // case the automatic selection cannot serve.
        assertTrue(recall.select("что приготовить на ужин?", java.time.Instant.now()).offered().isEmpty(),
                "the automatic selection is expected to miss this");

        ToolResult result = recall("вегетарианство мясо", "Саша");

        assertTrue(result.success());
        assertTrue(result.content().contains("не ест мясо"), result.content());
    }

    @Test
    void recallThatFoundNothingSaysSoAndNamesWhoIsKnown() {
        memory.addEntity("Саша", List.of(), "друг", "");

        ToolResult result = recall("подводное плавание", "");

        assertFalse(result.success(), "an empty recall must not look like an answer");
        assertTrue(result.content().contains("Саша"), "the model should be able to say what it does know");
        assertTrue(result.content().contains("do not make something up"), result.content());
    }

    @Test
    void rememberWritesImmediatelyAndTheNextMessageSeesIt() {
        ToolResult result = remember(Map.of("text", "Пользователь переехал в Москву",
                "category", "trait", "about", List.of()));

        assertTrue(result.success(), result.content());
        assertEquals(1, memory.factsAboutUser().size());
        assertTrue(recall.select("в каком городе я живу?", java.time.Instant.now())
                        .block().contains("переехал в Москву"),
                "a fact written on request must be in the very next prompt, not after consolidation");
    }

    @Test
    void rememberingAboutSomebodyNewCreatesThePerson() {
        remember(Map.of("text", "Марина держит кота", "category", "trait", "about", List.of("Марина")));

        Entity marina = memory.findByName("Марина").orElseThrow();
        assertEquals(1, memory.factsOf(marina.id()).size());
    }

    @Test
    void sayingTheSameThingAgainConfirmsItInsteadOfDuplicatingIt() {
        remember(Map.of("text", "Пользователь пьёт кофе без сахара", "category", "preference"));
        ToolResult again = remember(Map.of("text", "Пользователь пьёт кофе без сахара", "category", "preference"));

        assertTrue(again.success());
        assertEquals(1, memory.factsAboutUser().size(), "the same fact must not be written twice");
        assertEquals(2, memory.factsAboutUser().getFirst().mentionCount(),
                "hearing it again is evidence, not noise");
    }

    @Test
    void correctionSupersedesTheOldFactAndKeepsIt() {
        Fact old = memory.addFact("Пользователь живёт в Казани", FactCategory.TRAIT, null, null, List.of());

        ToolResult result = remember(Map.of("text", "Пользователь живёт в Москве", "category", "trait",
                "replaces", old.id()));

        assertTrue(result.success(), result.content());
        assertEquals(List.of("Пользователь живёт в Москве"),
                memory.factsAboutUser().stream().map(Fact::text).toList(),
                "the contradiction must not sit next to what it contradicts");
        assertFalse(memory.fact(old.id()).orElseThrow().isCurrent());
        assertEquals(1, memory.supersededFacts(10).size(), "nothing is deleted -- the history stays");
    }

    @Test
    void forgettingStopsAFactWithoutErasingIt() {
        Fact wrong = memory.addFact("Пользователь курит", FactCategory.TRAIT, null, null, List.of());

        ToolResult result = forget(wrong.id());

        assertTrue(result.success(), result.content());
        assertTrue(memory.factsAboutUser().isEmpty());
        assertTrue(memory.fact(wrong.id()).isPresent(), "the fact is kept in the history");
        assertFalse(memory.fact(wrong.id()).orElseThrow().isCurrent());
    }

    @Test
    void forgettingSomethingThatWasNeverThereIsAnError() {
        ToolResult result = forget(4242);

        assertFalse(result.success(), "claiming to have forgotten what was never known is worse than an error");
        assertTrue(result.content().contains("recall"), result.content());
    }

    @Test
    void factNumbersSurviveTheModelWritingThemWithAHash() {
        Fact old = memory.addFact("Пользователь работает в банке", FactCategory.TRAIT, null, null, List.of());

        assertTrue(forget("#" + old.id()).success(), "models write ids the way they saw them");
        assertFalse(memory.fact(old.id()).orElseThrow().isCurrent());
    }
}
