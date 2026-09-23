package com.bebebe.agent.core;

import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding a fact by what it means, and staying out of the way when there is nothing to do it with. */
class SemanticRecallTest {

    @TempDir
    Path temp;

    private MemoryStore memory;
    private TestEmbeddings embeddings;
    private SemanticRecall semantic;

    @BeforeEach
    void setUp() {
        memory = TestMemory.inDirectory(temp);
        embeddings = new TestEmbeddings();
        semantic = new SemanticRecall(memory, embeddings);
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private Fact fact(String text) {
        return memory.addFact(text, FactCategory.TRAIT, null, null, List.of());
    }

    @Test
    void aFactIsFoundByAQuestionThatSharesNoWordWithIt() {
        Fact vegetarian = fact("Саша не ест мясо");
        fact("Саша живёт в Питере");
        assertEquals(2, semantic.backfill());

        List<Fact> found = semantic.similar("кто из моих знакомых вегетарианец?", 3);

        assertFalse(found.isEmpty(), "this is exactly the miss the eval has been printing");
        assertEquals(vegetarian.id(), found.getFirst().id());
    }

    @Test
    void somethingAboutAnotherSubjectIsNotOffered() {
        fact("Саша живёт в Питере");
        semantic.backfill();

        assertTrue(semantic.similar("кто играет на гитаре?", 3).isEmpty(),
                "an irrelevant fact offered as a memory is worse than none: the model will use it");
    }

    @Test
    void whatIsNoLongerCurrentIsNotFound() {
        Fact old = fact("Пользователь живёт в Казани");
        semantic.backfill();
        memory.supersede(old.id(), null, "переехал");

        assertTrue(semantic.similar("в каком городе я живу?", 3).isEmpty());
    }

    @Test
    void newFactsAreEmbeddedOnTheNextPassAndOldOnesAreNotRedone() {
        fact("Саша не ест мясо");
        assertEquals(1, semantic.backfill());
        assertEquals(0, semantic.backfill(), "nothing left to do");

        fact("Марина боится собак");
        assertEquals(1, semantic.backfill(), "only the new one");
        assertEquals(2, embeddings.calls(), "two facts, and nothing embedded twice");
    }

    @Test
    void afterEmbeddingMoreFactsTheSearchSeesThem() {
        fact("Саша не ест мясо");
        semantic.backfill();
        semantic.similar("вегетарианец", 3);

        fact("Марина боится собак");
        semantic.backfill();

        assertFalse(semantic.similar("кто боится щенков?", 3).isEmpty(),
                "the cache has to notice what was embedded after it was filled");
    }

    @Test
    void withNothingEmbeddedYetTheSearchIsSimplySilent() {
        fact("Саша не ест мясо");

        assertTrue(semantic.similar("вегетарианец", 3).isEmpty());
    }

    @Test
    void withoutAnEmbeddingModelNothingIsCalledAndNothingBreaks() {
        SemanticRecall off = new SemanticRecall(memory, null);
        fact("Саша не ест мясо");

        assertFalse(off.isReady());
        assertEquals(0, off.backfill());
        assertTrue(off.similar("вегетарианец", 3).isEmpty());
    }

    @Test
    void cosineIsWhatItSaysItIs() {
        float[] a = {1, 0, 1};
        float[] b = {1, 0, 1};
        float[] c = {0, 1, 0};

        assertEquals(1.0, SemanticRecall.cosine(a, b), 1e-9);
        assertEquals(0.0, SemanticRecall.cosine(a, c), 1e-9);
        assertEquals(0.0, SemanticRecall.cosine(a, new float[]{1, 0}), 1e-9, "different lengths do not compare");
        assertEquals(0.0, SemanticRecall.cosine(new float[]{0, 0}, new float[]{0, 0}), 1e-9);
    }

    @Test
    void vectorsSurviveTheRoundTripThroughTheDatabase() {
        float[] original = {0.5f, -0.25f, 0f, 1f};
        memory.saveVector(fact("Саша не ест мясо").id(), "test", original);

        float[] back = memory.vectors("test").values().iterator().next();

        assertEquals(original.length, back.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals(original[i], back[i], 1e-9);
        }
    }

    @Test
    void vectorsOfAnotherModelAreNotMixedIn() {
        long id = fact("Саша не ест мясо").id();
        memory.saveVector(id, "model-a", new float[]{1, 0});
        memory.saveVector(id, "model-b", new float[]{0, 1});

        assertEquals(1, memory.vectors("model-a").size());
        assertTrue(memory.factsWithoutVectors("model-c", 10).stream().anyMatch(f -> f.id() == id),
                "switching the model means everything has to be embedded again");
    }
}
