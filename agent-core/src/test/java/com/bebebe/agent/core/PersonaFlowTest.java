package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.Persona;
import com.bebebe.agent.memory.PersonaStore;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.clipboard.ReadClipboardTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonaFlowTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ScriptLibrary library;
    private MemoryStore memory;
    private PersonaStore personas;
    private AgentCore core;
    private String clipboard = "Текст из буфера";

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
        personas = new PersonaStore(temp.resolve("personas.db"));
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
        ToolRegistry tools = new ToolRegistry().register(new ReadClipboardTool(() -> Optional.ofNullable(clipboard)));
        core = new AgentCore(agentSwitch, ollama, scripts, library, memory, tools, null, Clock.systemDefaultZone(), 15);
        core.setPersonas(personas);
    }

    @AfterEach
    void tearDown() {
        personas.close();
        memory.close();
        library.close();
        ollama.close();
        stub.close();
    }

    private String systemPromptOf(int request) {
        return stub.requests().get(request).get("messages").get(0).get("content").asText();
    }

    @Test
    void activePersonaIsMixedIntoDecisionAndFormulationPrompts() {
        Persona pirate = personas.create("Пират", "Говори как пират, добавляй «йо-хо-хо».");
        personas.activate(pirate.id());
        stub.enqueue("""
                {"type":"tool_call","tool_name":"read_clipboard","arguments":{}}""");
        stub.enqueuePlain("Йо-хо-хо, вот твой текст.");

        core.handle(UserMessage.telegram("возьми то, что я скопировал, и покажи", 1L));

        assertTrue(systemPromptOf(0).contains("Persona «Пират»"), systemPromptOf(0));
        assertTrue(systemPromptOf(0).contains("йо-хо-хо"), systemPromptOf(0));
        assertTrue(systemPromptOf(1).contains("Persona «Пират»"), "and on the answer formulation too");
    }

    @Test
    void switchingPersonaAffectsNextReply() {
        stub.enqueueReply("ок");
        core.handle(UserMessage.telegram("привет", 1L));
        assertTrue(systemPromptOf(0).contains("Persona «Default»"));

        Persona pirate = personas.create("Пират", "Арр!");
        personas.activate(pirate.id());
        stub.enqueueReply("Арр!");
        core.handle(UserMessage.telegram("ещё раз", 1L));

        assertTrue(systemPromptOf(1).contains("Persona «Пират»"), systemPromptOf(1));
        assertFalse(systemPromptOf(1).contains("Persona «Default»"));
    }

    @Test
    void clipboardContentGoesToSessionLogWithClipboardSource() {
        stub.enqueue("""
                {"type":"tool_call","tool_name":"read_clipboard","arguments":{}}""");
        stub.enqueuePlain("Вот что скопировано: Текст из буфера");

        core.handle(UserMessage.telegram("переведи то, что я скопировал", 1L));

        var session = memory.activeSession("TELEGRAM:1").orElseThrow();
        var messages = memory.messages(session.id());
        assertEquals(List.of("TELEGRAM", "CLIPBOARD", "TELEGRAM"),
                messages.stream().map(m -> m.source()).toList(), messages.toString());
        assertTrue(messages.get(1).text().contains("Текст из буфера"));

        stub.enqueueReply("Перевод...");
        core.handle(UserMessage.telegram("а теперь на английский", 1L));
        String history = stub.requests().get(2).get("messages").toString();
        assertTrue(history.contains("Текст из буфера"), history);
    }

    @Test
    void emptyClipboardIsNotLogged() {
        clipboard = null;
        stub.enqueue("""
                {"type":"tool_call","tool_name":"read_clipboard","arguments":{}}""");
        stub.enqueuePlain("Буфер пуст.");

        core.handle(UserMessage.telegram("возьми из буфера", 1L));

        var session = memory.activeSession("TELEGRAM:1").orElseThrow();
        assertTrue(memory.messages(session.id()).stream().noneMatch(m -> "CLIPBOARD".equals(m.source())));
    }
}
