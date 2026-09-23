package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.MessageRole;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidatorTest {

    private static final String CHAT = "TELEGRAM:1";

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private MemoryStore memory;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        memory = TestMemory.inDirectory(temp);
        consolidator = new MemoryConsolidator(memory, () -> new com.bebebe.agent.llm.OllamaProvider(ollama));
    }

    @AfterEach
    void tearDown() {
        memory.close();
        ollama.close();
        stub.close();
    }

    private DialogSession sessionWith(String... userTexts) {
        DialogSession session = memory.openOrContinue(CHAT);
        for (String text : userTexts) {
            memory.append(session.id(), MessageRole.USER, text, "TELEGRAM", "t");
            memory.append(session.id(), MessageRole.ASSISTANT, "ок", "TELEGRAM", "t");
        }
        return memory.session(session.id()).orElseThrow();
    }

    /**
     * A contradiction used to be filed next to what it contradicted, and the model was left to
     * work out which of two equally presented facts it was supposed to believe.
     */
    @Test
    void aFactThatContradictsAKnownOneRetiresIt() {
        Fact old = memory.addFact("Пользователь живёт в Казани", FactCategory.TRAIT, null, null, List.of());
        stub.enqueue("""
                {"entities":[],
                 "facts":[{"text":"Пользователь живёт в Москве","category":"trait","date":"",
                           "entities":[],"replaces":[%d]}]}""".formatted(old.id()));

        consolidator.consolidate(sessionWith("я переехал в Москву"));

        assertEquals(List.of("Пользователь живёт в Москве"),
                memory.factsAboutUser().stream().map(Fact::text).toList());
        assertFalse(memory.fact(old.id()).orElseThrow().isCurrent());
        assertEquals(1, memory.supersededFacts(10).size(), "nothing is deleted");
    }

    /** The model happily names facts it was never shown; those numbers must do nothing. */
    @Test
    void anInventedReplacementNumberIsIgnored() {
        Fact kept = memory.addFact("Пользователь живёт в Казани", FactCategory.TRAIT, null, null, List.of());
        stub.enqueue("""
                {"entities":[],
                 "facts":[{"text":"Пользователь купил велосипед","category":"event","date":"",
                           "entities":[],"replaces":[9999]}]}""");

        consolidator.consolidate(sessionWith("купил велосипед"));

        assertTrue(memory.fact(kept.id()).orElseThrow().isCurrent());
        assertEquals(0, memory.supersededFacts(10).size());
    }

    /**
     * Extraction runs on overlapping tails, so the same thing arrives again as a matter of course.
     * It is not written twice -- but it is no longer thrown away unrecorded either.
     */
    @Test
    void hearingTheSameFactAgainCountsAsAConfirmation() {
        stub.enqueue("""
                {"entities":[],
                 "facts":[{"text":"Пользователь пьёт кофе без сахара","category":"preference",
                           "date":"","entities":[]}]}""");
        consolidator.consolidate(sessionWith("пью кофе без сахара"));
        assertEquals(1, memory.factsAboutUser().size());

        stub.enqueue("""
                {"entities":[],
                 "facts":[{"text":"Пользователь пьёт кофе без сахара","category":"preference",
                           "date":"","entities":[]}]}""");
        consolidator.consolidate(sessionWith("я же говорил, кофе без сахара"));

        assertEquals(1, memory.factsAboutUser().size(), "still one fact");
        assertEquals(2, memory.factsAboutUser().getFirst().mentionCount(),
                "but it has been confirmed twice, and the ranking can see that");
    }

    /** What is already remembered has to be shown, or there is nothing for "replaces" to point at. */
    @Test
    void extractionSeesWhatIsAlreadyRemembered() {
        memory.addFact("Пользователь живёт в Казани", FactCategory.TRAIT, null, null, List.of());
        stub.enqueue("""
                {"entities":[],"facts":[]}""");

        consolidator.consolidate(sessionWith("я переехал"));

        assertTrue(stub.requests().getLast().toString().contains("живёт в Казани"),
                "the model cannot contradict a fact it was never shown");
    }

    @Test
    void newPersonAndFactAreRecorded() {
        stub.enqueue("""
                {"entities":[{"name":"Саша","relation":"друг","aliases":["Александр"],
                              "match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Саша не ест мясо","category":"preference","date":"","entities":["Саша"]}]}""");

        List<AgentReply.EntityQuestion> questions = consolidator.consolidate(sessionWith("Саша не ест мясо"));

        assertTrue(questions.isEmpty());
        Entity sasha = memory.findByName("Саша").orElseThrow();
        assertEquals("друг", sasha.relation());
        assertEquals(List.of("Александр"), sasha.aliases());
        List<Fact> facts = memory.factsOf(sasha.id());
        assertEquals(1, facts.size());
        assertEquals(FactCategory.PREFERENCE, facts.getFirst().category());
    }

    @Test
    void confidentMatchLinksToKnownPerson() {
        Entity known = memory.addEntity("Александр", List.of(), "коллега", "");
        stub.enqueue("""
                {"entities":[{"name":"Саня","relation":"","aliases":[],
                              "match":{"entity_id":%d,"confidence":"high"}}],
                 "facts":[{"text":"Саня переехал в Казань","category":"trait","date":"","entities":["Саня"]}]}"""
                .formatted(known.id()));

        consolidator.consolidate(sessionWith("Саня переехал в Казань"));

        assertEquals(1, memory.countEntities(), "no new person expected");
        Entity enriched = memory.entity(known.id()).orElseThrow();
        assertTrue(enriched.aliases().contains("Саня"), "the new spelling must become an alias");
        assertEquals(1, memory.factsOf(known.id()).size());
    }

    @Test
    void uncertainMatchAsksQuestionAndHoldsFacts() {
        Entity known = memory.addEntity("Саша", List.of(), "друг", "");
        stub.enqueue("""
                {"entities":[{"name":"Саша","relation":"коллега","aliases":[],
                              "match":{"entity_id":%d,"confidence":"low"}}],
                 "facts":[{"text":"Саша ведёт проект X","category":"trait","date":"","entities":["Саша"]},
                          {"text":"Пользователь любит чай","category":"preference","date":"","entities":[]}]}"""
                .formatted(known.id()));

        stub.enqueue("""
                {"entities":[{"name":"Александр","relation":"коллега","aliases":[],
                              "match":{"entity_id":%d,"confidence":"low"}}],
                 "facts":[{"text":"Александр ведёт проект X","category":"trait","date":"","entities":["Александр"]},
                          {"text":"Пользователь пьёт кофе без сахара","category":"preference","date":"","entities":[]}]}"""
                .formatted(known.id()));

        consolidator.consolidate(sessionWith("Саша ведёт проект X"));
        List<AgentReply.EntityQuestion> questions = consolidator.consolidate(sessionWith("Александр ведёт проект X"));

        assertEquals(1, questions.size());
        AgentReply.EntityQuestion question = questions.getFirst();
        assertEquals("Александр", question.mention());
        assertEquals(known.id(), question.candidate().id());
        assertEquals(1, question.heldFacts(), "the fact about Alexander must wait for the answer");

        assertEquals(1, memory.factsAboutUser().stream().filter(f -> f.text().contains("кофе")).count());
        assertTrue(memory.factsOf(known.id()).stream().noneMatch(f -> f.text().contains("Александр")));
        assertEquals(1, consolidator.pendingQuestions());
    }

    @Test
    void answerSameLinksHeldFacts() {
        Entity known = memory.addEntity("Саша", List.of(), "друг", "");
        stub.enqueue("""
                {"entities":[{"name":"Александр","relation":"","aliases":[],"match":{"entity_id":%d,"confidence":"low"}}],
                 "facts":[{"text":"Александр купил машину","category":"event","date":"2026-09-10","entities":["Александр"]}]}"""
                .formatted(known.id()));
        AgentReply.EntityQuestion question = consolidator.consolidate(sessionWith("Александр купил машину")).getFirst();

        AgentReply reply = consolidator.resolve(question.token(), true);

        assertTrue(reply.asPlainText().contains("Саша"), reply.asPlainText());
        assertEquals(1, memory.countEntities());
        assertTrue(memory.entity(known.id()).orElseThrow().aliases().contains("Александр"));
        Fact fact = memory.factsOf(known.id()).getFirst();
        assertEquals(LocalDate.of(2026, 9, 10), fact.factDate());
        assertEquals(0, consolidator.pendingQuestions());
    }

    @Test
    void answerDifferentCreatesNewPerson() {
        Entity known = memory.addEntity("Саша", List.of(), "друг", "");
        stub.enqueue("""
                {"entities":[{"name":"Александр","relation":"коллега","aliases":[],"match":{"entity_id":%d,"confidence":"low"}}],
                 "facts":[{"text":"Александр ведёт проект","category":"trait","date":"","entities":["Александр"]}]}"""
                .formatted(known.id()));
        AgentReply.EntityQuestion question = consolidator.consolidate(sessionWith("Александр ведёт проект")).getFirst();

        consolidator.resolve(question.token(), false);

        assertEquals(2, memory.countEntities());
        Entity created = memory.findByName("Александр").orElseThrow();
        assertEquals("коллега", created.relation());
        assertEquals(1, memory.factsOf(created.id()).size());
        assertTrue(memory.factsOf(known.id()).isEmpty());
    }

    @Test
    void procedureIsRecordedWithItsCategory() {
        stub.enqueue("""
                {"entities":[],
                 "facts":[{"text":"Перед стримом: запустить OBS, включить свет, открыть чат",
                           "category":"procedure","date":"","entities":[]}]}""");

        consolidator.consolidate(sessionWith("Запомни: перед стримом запускай OBS, включай свет и открывай чат"));

        List<Fact> procedures = memory.factsByCategory(FactCategory.PROCEDURE);
        assertEquals(1, procedures.size());
        assertTrue(procedures.getFirst().text().contains("OBS"));
    }

    /**
     * This used to assert the opposite -- that the tail is marked even when extraction fails --
     * and that is precisely what emptied memory in practice: a model answering in fenced JSON
     * failed every run and burned every message it was handed. Losing the messages is worse
     * than paying for one more attempt, so they are now kept.
     */
    @Test
    void whenExtractionFailsTheMessagesAreKeptForTheNextRun() {

        stub.enqueue("это не json");

        DialogSession session = sessionWith("что-то");
        consolidator.consolidate(session);

        assertFalse(memory.unconsolidated(memory.session(session.id()).orElseThrow()).isEmpty(),
                "the tail must survive a failed extraction");
        assertEquals(0, memory.countFacts());
    }

    @Test
    void aFailedTailIsNotRetriedUntilItGrows() {

        stub.enqueue("это не json");
        DialogSession session = sessionWith("что-то");
        consolidator.consolidate(session);
        int afterFirst = stub.callCount();

        consolidator.consolidate(memory.session(session.id()).orElseThrow());

        assertEquals(afterFirst, stub.callCount(),
                "the same tail must not be sent again -- a broken model would be asked on every message");
    }

    @Test
    void jsonWrappedInMarkdownFencesIsStillUnderstood() {

        stub.enqueue("""
                ```json
                {"entities":[],"facts":[{"text":"пьёт чай без сахара","category":"preference",
                 "entities":[],"date":""}]}
                ```""");

        consolidator.consolidate(sessionWith("я пью чай без сахара"));

        assertEquals(1, memory.countFacts(), "a fenced answer is what the live model actually sent");
    }

    @Test
    void theSameFactInSlightlyDifferentWordsIsNotStoredTwice() {
        stub.enqueue("""
                {"entities":[{"name":"Саша","relation":"друг","aliases":[],
                              "match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Саша не ест мясо","category":"preference","date":"","entities":["Саша"]}]}""");
        consolidator.consolidate(sessionWith("Саша не ест мясо"));
        assertEquals(1, memory.countFacts());

        stub.enqueue("""
                {"entities":[{"name":"Саша","relation":"друг","aliases":[],
                              "match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Саша не ест мяса","category":"preference","date":"","entities":["Саша"]}]}""");
        consolidator.consolidate(sessionWith("Саша опять не ест мяса"));

        assertEquals(1, memory.countFacts(), "the same thing said again must not become a second fact");
    }

    @Test
    void aDetailAddedToAKnownFactIsStillANewFact() {
        stub.enqueue("""
                {"entities":[],"facts":[{"text":"пьёт чай","category":"preference","date":"","entities":[]}]}""");
        consolidator.consolidate(sessionWith("я пью чай"));

        stub.enqueue("""
                {"entities":[],
                 "facts":[{"text":"пьёт чай только зелёный и без сахара","category":"preference",
                           "date":"","entities":[]}]}""");
        consolidator.consolidate(sessionWith("чай только зелёный, без сахара"));

        assertEquals(2, memory.countFacts(), "a longer, more specific wording carries new information");
    }

    @Test
    void aMisspeltNameIsMatchedWithoutAskingTheUser() {
        memory.addEntity("Александр", List.of(), "друг", "");
        stub.enqueue("""
                {"entities":[{"name":"Алексанрд","relation":"","aliases":[],
                              "match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Алексанрд купил велосипед","category":"event","date":"","entities":["Алексанрд"]}]}""");

        List<AgentReply.EntityQuestion> questions = consolidator.consolidate(sessionWith("Алексанрд купил велосипед"));

        assertTrue(questions.isEmpty(), "a typo is not worth a question");
        assertEquals(1, memory.countEntities(), "and must not create a second person");
        Entity sasha = memory.findByName("Александр").orElseThrow();
        assertTrue(sasha.aliases().contains("Алексанрд"), "the spelling is remembered as an alias");
        assertEquals(1, memory.factsOf(sasha.id()).size());
    }

    @Test
    void withoutUserMessagesModelIsNotCalled() {
        DialogSession session = memory.openOrContinue(CHAT);
        memory.append(session.id(), MessageRole.ASSISTANT, "только агент", "T", null);

        consolidator.consolidate(memory.session(session.id()).orElseThrow());

        assertEquals(0, stub.callCount());
    }

    @Test
    void knownPeopleGetIntoExtractionPrompt() {
        memory.addEntity("Саша", List.of("Шурик"), "друг", "");
        stub.enqueue("{\"entities\":[],\"facts\":[]}");

        consolidator.consolidate(sessionWith("привет"));

        String request = stub.requests().getFirst().toString();
        assertTrue(request.contains("Саша"), "the model must see known people for matching");
        assertTrue(request.contains("Шурик"));
        assertTrue(request.contains("additionalProperties"), "the schema must go into format");
    }
}
