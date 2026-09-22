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

    @Test
    void consolidationMarksTailEvenOnError() {

        stub.enqueue("это не json");

        DialogSession session = sessionWith("что-то");
        consolidator.consolidate(session);

        assertTrue(memory.unconsolidated(memory.session(session.id()).orElseThrow()).isEmpty());
        assertEquals(0, memory.countFacts());
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
