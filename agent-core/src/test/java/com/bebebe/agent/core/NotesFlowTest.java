package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.notes.NotesConfig;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.notes.NotesTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesFlowTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ScriptLibrary library;
    private MemoryStore memory;
    private NotesStore notes;
    private AgentCore core;

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
        memory = TestMemory.inDirectory(temp, 1000);
        notes = new NotesStore(new NotesConfig(temp.resolve("notes"), false));
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
        ToolRegistry tools = new ToolRegistry().register(new NotesTool(notes, null, Clock.systemDefaultZone()));
        core = new AgentCore(agentSwitch, ollama, scripts, library, memory, tools, null, Clock.systemDefaultZone(), 15);
    }

    @AfterEach
    void tearDown() {
        notes.close();
        memory.close();
        library.close();
        ollama.close();
        stub.close();
    }

    @Test
    void addToTaskListCreatesFileAndReplies() throws IOException {
        stub.enqueue("""
                {"type":"tool_call","tool_name":"notes_tool","arguments":
                  {"action":"add_item","name":"задачи","text":"купить хлеб"}}""");
        stub.enqueuePlain("Записал «купить хлеб» в список задач.");

        AgentReply reply = core.handle(UserMessage.telegram("запиши в список задач купить хлеб", 1L));

        assertEquals("Записал «купить хлеб» в список задач.", reply.asPlainText());
        String file = Files.readString(temp.resolve("notes/lists/задачи.md"));
        assertTrue(file.contains("- [ ] купить хлеб"), file);
        assertEquals(2, stub.callCount(), "decision + formulation, no confirmation");
    }

    @Test
    void ambiguousListModelSeesOptionsAndAsks() {
        notes.createList("Задачи по дому", List.of());
        notes.createList("Задачи по работе", List.of());
        stub.enqueue("""
                {"type":"tool_call","tool_name":"notes_tool","arguments":
                  {"action":"add_item","name":"задачи","text":"купить хлеб"}}""");
        stub.enqueuePlain("В какой список: «Задачи по дому» или «Задачи по работе»?");

        AgentReply reply = core.handle(UserMessage.telegram("запиши в задачи купить хлеб", 1L));

        assertTrue(reply.asPlainText().startsWith("В какой список"), reply.asPlainText());
        String second = stub.requests().get(1).toString();
        assertTrue(second.contains("Several similar documents"), second);
        assertTrue(notes.all().stream().allMatch(d -> d.items().isEmpty()), "nothing added at random");
    }

    @Test
    void directQuestionAboutNoteGoesToToolAndVerdictReachesFormulation() {
        notes.createNote("Рецепт борща", "свёкла", List.of());

        stub.enqueue("""
                {"type":"tool_call","tool_name":"notes_tool","arguments":{"action":"find","query":"рецепт борща"}}""");
        stub.enqueuePlain("Да, есть заметка «Рецепт борща»: свёкла.");
        AgentReply yes = core.handle(UserMessage.telegram("есть ли у меня заметка с именем рецепт борща?", 1L));
        assertTrue(yes.asPlainText().startsWith("Да, есть"), yes.asPlainText());
        String decision = stub.requests().getFirst().toString();
        assertTrue(decision.contains("is there a note/list X"), "tool description in the prompt: " + decision);
        assertTrue(decision.contains("do not answer from conversation memory"), decision);
        assertTrue(stub.requests().get(1).toString().contains("Found, the title matches exactly: «Рецепт борща»"));

        stub.enqueue("""
                {"type":"tool_call","tool_name":"notes_tool","arguments":{"action":"find","query":"рецепт пирога"}}""");
        stub.enqueuePlain("Такой заметки нет; есть только «Рецепт борща».");
        AgentReply no = core.handle(UserMessage.telegram("а заметка рецепт пирога есть?", 1L));
        assertTrue(no.asPlainText().startsWith("Такой заметки нет"), no.asPlainText());
        String summary = stub.requests().get(3).toString();
        assertTrue(summary.contains("NO document «рецепт пирога»"), summary);
        assertTrue(summary.contains("«Рецепт борща» (word «рецепт»)"), summary);
        assertEquals(4, stub.callCount(), "two questions, two calls each, no confirmations");
    }
}
