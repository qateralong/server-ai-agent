package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.DialogMessage;
import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.MessageRole;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryIntegrationTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ScriptLibrary library;
    private MemoryStore memory;
    private ActionExecutor scripts;
    private final List<AgentCore.Outbound> outbound = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        agentSwitch = new AgentSwitch(true);
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("lib.db"), temp.resolve("lib"))).section(LibraryConfig.SECTION)));
        scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
    }

    @AfterEach
    void tearDown() {
        if (memory != null) {
            memory.close();
        }
        library.close();
        ollama.close();
        stub.close();
    }

    private AgentCore core(int consolidateEvery) {
        memory = TestMemory.inDirectory(temp, consolidateEvery);
        AgentCore core = new AgentCore(agentSwitch, ollama, scripts, library, memory);
        core.setNotifier(outbound::add);
        return core;
    }

    private static UserMessage ask(String text) {
        return UserMessage.telegram(text, 7L);
    }

    @Test
    void messagesAreWrittenToSessionLog() {
        AgentCore core = core(100);
        stub.enqueueReply("Привет!");

        core.handle(ask("привет"));

        DialogSession session = memory.activeSession("TELEGRAM:7").orElseThrow();
        List<DialogMessage> log = memory.messages(session.id());
        assertEquals(2, log.size());
        assertEquals(MessageRole.USER, log.get(0).role());
        assertEquals("привет", log.get(0).text());
        assertEquals("Привет!", log.get(1).text());
    }

    @Test
    void wholeSessionLogGoesToModelAsPreviousMessages() {
        AgentCore core = core(100);
        stub.enqueueReply("Свободно 40 ГБ.");
        stub.enqueueReply("В гигабайтах: 40.");

        core.handle(ask("сколько места на диске?"));
        core.handle(ask("а в гигабайтах?"));

        JsonNode second = stub.requests().get(1);
        JsonNode messages = second.get("messages");

        assertEquals(4, messages.size(), messages.toString());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("сколько места на диске?", messages.get(1).get("content").asText());
        assertEquals("assistant", messages.get(2).get("role").asText());
        assertEquals("Свободно 40 ГБ.", messages.get(2).get("content").asText());
        assertTrue(messages.get(3).get("content").asText().startsWith("а в гигабайтах?"));
    }

    @Test
    void factsOfMentionedPersonGetIntoSystemPrompt() {
        AgentCore core = core(100);
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");
        memory.addFact("Саша не ест мясо", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));
        stub.enqueueReply("Тогда без мяса.");

        core.handle(ask("что приготовить Саше на ужин?"));

        String system = stub.requests().getFirst().get("messages").get(0).get("content").asText();
        assertTrue(system.contains("Саша"), system);
        assertTrue(system.contains("не ест мясо"), system);
    }

    @Test
    void unmentionedPeopleStayOutOfPrompt() {
        AgentCore core = core(100);
        Entity petya = memory.addEntity("Петя", List.of(), "", "");
        memory.addFact("Петя живёт в Казани", FactCategory.TRAIT, null, null, List.of(petya.id()));
        stub.enqueueReply("ок");

        core.handle(ask("какая погода?"));

        String system = stub.requests().getFirst().get("messages").get(0).get("content").asText();
        assertFalse(system.contains("Казани"), "foreign facts must not bloat the context");
    }

    @Test
    void proceduresAndUserFactsAreAlwaysInContext() {
        AgentCore core = core(100);
        memory.addFact("Перед стримом: OBS, свет, чат", FactCategory.PROCEDURE, null, null, List.of());
        memory.addFact("Не пьёт кофе", FactCategory.PREFERENCE, null, null, List.of());
        stub.enqueueReply("ок");

        core.handle(ask("что-нибудь"));

        String system = stub.requests().getFirst().get("messages").get(0).get("content").asText();
        assertTrue(system.contains("Перед стримом"));
        assertTrue(system.contains("Не пьёт кофе"));
    }

    @Test
    void consolidationRunsEveryNMessages() {
        AgentCore core = core(2);
        stub.enqueueReply("ок");
        stub.enqueue("""
                {"entities":[{"name":"Саша","relation":"друг","aliases":[],"match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Саша не ест мясо","category":"preference","date":"","entities":["Саша"]}]}""");

        core.handle(ask("Саша не ест мясо, запомни"));

        assertEquals(2, stub.callCount(), "reply + extraction");
        assertTrue(memory.findByName("Саша").isPresent());
        assertEquals(1, memory.countFacts());
        assertTrue(memory.unconsolidated(memory.activeSession("TELEGRAM:7").orElseThrow()).isEmpty());
    }

    @Test
    void noConsolidationBelowThreshold() {
        AgentCore core = core(10);
        stub.enqueueReply("ок");

        core.handle(ask("привет"));

        assertEquals(1, stub.callCount());
        assertEquals(2, memory.unconsolidated(memory.activeSession("TELEGRAM:7").orElseThrow()).size());
    }

    @Test
    void stoppingConsolidatesAndClosesSessionImmediately() {
        AgentCore core = core(100);
        stub.enqueueReply("ок");
        core.handle(ask("Петя переехал в Питер"));
        assertEquals(1, stub.callCount());

        stub.enqueue("""
                {"entities":[{"name":"Петя","relation":"","aliases":[],"match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Петя переехал в Питер","category":"event","date":"","entities":["Петя"]}]}""");

        agentSwitch.turnOff();

        assertEquals(2, stub.callCount(), "onBeforeStop must trigger extraction immediately");
        assertTrue(memory.findByName("Петя").isPresent());
        assertTrue(memory.activeSession("TELEGRAM:7").isEmpty(), "session must be closed");
        assertEquals(0, memory.activeSessions().size());
    }

    @Test
    void memoryQuestionGoesToSameConversation() {
        AgentCore core = core(2);
        Entity known = memory.addEntity("Саша", List.of(), "друг", "");
        stub.enqueueReply("ок");
        stub.enqueue("""
                {"entities":[{"name":"Александр","relation":"","aliases":[],"match":{"entity_id":%d,"confidence":"low"}}],
                 "facts":[{"text":"Александр купил велосипед","category":"event","date":"","entities":["Александр"]}]}"""
                .formatted(known.id()));

        core.handle(ask("Александр купил велосипед"));

        assertEquals(1, outbound.size());
        assertEquals("TELEGRAM:7", outbound.getFirst().conversationKey());
        AgentReply.EntityQuestion question = (AgentReply.EntityQuestion) outbound.getFirst().reply();
        assertEquals("Александр", question.mention());

        AgentReply answer = core.resolveEntity(question.token(), true);
        assertTrue(answer.asPlainText().contains("Саша"));
        assertEquals(1, memory.factsOf(known.id()).size());
    }

    @Test
    void afterSessionCloseNewOneStartsWithCleanHistory() {
        AgentCore core = core(100);
        stub.enqueueReply("раз");
        core.handle(ask("первое"));
        stub.enqueue("{\"entities\":[],\"facts\":[]}");
        agentSwitch.turnOff();
        agentSwitch.turnOn();

        stub.enqueueReply("два");
        core.handle(ask("второе"));

        JsonNode messages = stub.requests().getLast().get("messages");
        assertEquals(2, messages.size(), "new session -- without old history: " + messages);
    }
}
