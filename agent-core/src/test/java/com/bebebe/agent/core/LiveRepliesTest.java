package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.time.GetCurrentTimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveRepliesTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private ScriptLibrary library;
    private MemoryStore memory;
    private AgentCore core;
    private volatile boolean live;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("lib.db"), temp.resolve("lib"))).section(LibraryConfig.SECTION)));
        memory = TestMemory.inDirectory(temp, 1000);
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
        core = new AgentCore(new AgentSwitch(true), ollama, scripts, library, memory,
                new ToolRegistry().register(new GetCurrentTimeTool()), null, Clock.systemDefaultZone(), 15);
        core.setLiveReplies(() -> live);
    }

    @AfterEach
    void tearDown() {
        memory.close();
        library.close();
        ollama.close();
        stub.close();
    }

    private static UserMessage ask(String text) {
        return UserMessage.telegram(text, 1L);
    }

    @Test
    void messagesArrayIsParsedAndJoinedIntoReply() {
        AgentDecision d = AgentDecision.parse("""
                {"type":"reply","messages":["Привет!","Как дела?"," "]}""", new ObjectMapper());

        assertEquals(DecisionType.REPLY, d.type());
        assertEquals(List.of("Привет!", "Как дела?"), d.replies());
        assertEquals("Привет!\n\nКак дела?", d.reply(), "single text -- for log, memory and voice");
        assertTrue(d.isUsable());
    }

    @Test
    void messagesWithoutTypeIsAlsoReply() {
        AgentDecision d = AgentDecision.parse("{\"messages\":[\"раз\",\"два\"]}", new ObjectMapper());

        assertEquals(DecisionType.REPLY, d.type());
        assertEquals(2, d.replies().size());
    }

    @Test
    void plainReplyIsSingleMessage() {
        AgentDecision d = AgentDecision.parse("{\"type\":\"reply\",\"reply\":\"Лиссабон\"}", new ObjectMapper());

        assertEquals(List.of("Лиссабон"), d.replies());
    }

    @Test
    void schemaContainsMessagesOnlyWhenStyleOn() {
        assertFalse(DecisionProtocol.responseSchema(new ToolRegistry(), false).toString().contains("messages"));
        assertTrue(DecisionProtocol.responseSchema(new ToolRegistry(), true).toString().contains("messages"));
    }

    @Test
    void withToggleOnReplyIsMultipartAndPromptHasRules() {
        live = true;
        stub.enqueue("{\"type\":\"reply\",\"messages\":[\"О, привет!\",\"Чем займёмся?\"]}");

        AgentReply reply = core.handle(ask("привет"));

        AgentReply.Text text = assertInstanceOf(AgentReply.Text.class, reply);
        assertTrue(text.isMultipart());
        assertEquals(List.of("О, привет!", "Чем займёмся?"), text.parts());
        String system = stub.requests().getFirst().get("messages").get(0).get("content").asText();
        assertTrue(system.contains("Lively message style is ON"), system);
        assertTrue(system.contains("persona instruction"), "the persona decides how to split");
        assertTrue(stub.requests().getFirst().get("format").toString().contains("messages"));
    }

    @Test
    void withToggleOffArrayIsJoinedIntoOneMessage() {
        live = false;
        stub.enqueue("{\"type\":\"reply\",\"messages\":[\"раз\",\"два\"]}");

        AgentReply.Text text = (AgentReply.Text) core.handle(ask("привет"));

        assertFalse(text.isMultipart());
        assertEquals("раз\n\nдва", text.text());
        String system = stub.requests().getFirst().get("messages").get(0).get("content").asText();
        assertFalse(system.contains("Lively"));
        assertFalse(stub.requests().getFirst().get("format").toString().contains("messages"));
    }

    @Test
    void freeAnswerAfterToolIsSplitBySeparator() {
        live = true;
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"get_current_time\",\"arguments\":{}}");
        stub.enqueuePlain("Сейчас суббота.\n---\nПолдень уже прошёл.\n---\nДень длинный впереди!");

        AgentReply.Text text = (AgentReply.Text) core.handle(ask("который час?"));

        assertEquals(3, text.parts().size());
        assertEquals("Полдень уже прошёл.", text.parts().get(1));
        String system = stub.requests().get(1).get("messages").get(0).get("content").asText();
        assertTrue(system.contains("---"), "separator hint in the formulation prompt");
    }

    @Test
    void withoutToggleSeparatorLeavesTextIntact() {
        live = false;
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"get_current_time\",\"arguments\":{}}");
        stub.enqueuePlain("Сейчас суббота.\n---\nПолдень.");

        AgentReply.Text text = (AgentReply.Text) core.handle(ask("который час?"));

        assertFalse(text.isMultipart());
        assertTrue(text.text().contains("---"));
    }
}
