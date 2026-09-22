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
import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolRegistry;
import com.bebebe.agent.tools.ToolResult;
import com.bebebe.agent.tools.time.GetCurrentTimeTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallFlowTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private ScriptLibrary library;
    private MemoryStore memory;
    private ActionExecutor scripts;

    private static final class ChattyTool implements Tool {
        private final int calls;
        ChattyTool(int calls) { this.calls = calls; }
        public String name() { return "chatty"; }
        public String description() { return "chatty"; }
        public Map<String, Object> parameters() { return Map.of(); }
        public ToolResult execute(Map<String, Object> a, ToolContext c) {
            for (int i = 0; i < calls; i++) {
                c.llm().ask("s", "u" + i, null);
            }
            return ToolResult.ok("tool data");
        }
    }

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
        scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
    }

    @AfterEach
    void tearDown() {
        memory.close();
        library.close();
        ollama.close();
        stub.close();
    }

    private AgentCore core(ToolRegistry tools, int budget) {
        return new AgentCore(new AgentSwitch(true), new com.bebebe.agent.llm.OllamaProvider(ollama), scripts, library, memory, tools, budget);
    }

    private static UserMessage ask(String text) {
        return UserMessage.telegram(text, 1L);
    }

    @Test
    void timeToolRunsWithoutConfirmation() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-19T11:30:00Z"), ZoneId.of("Europe/Moscow"));
        ToolRegistry tools = new ToolRegistry().register(new GetCurrentTimeTool(fixed));
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"get_current_time\",\"arguments\":{}}");
        stub.enqueuePlain("Сегодня суббота, 19 сентября.");

        AgentReply reply = core(tools, 15).handle(ask("какой сегодня день?"));

        assertInstanceOf(AgentReply.Text.class, reply, "a tool must not ask for confirmation");
        assertEquals("Сегодня суббота, 19 сентября.", reply.asPlainText());
        assertEquals(2, stub.callCount(), "decision + formulation");

        assertTrue(stub.requests().get(1).toString().contains("Saturday"));
    }

    @Test
    void toolModelCallsComeFromSharedBudget() {
        ToolRegistry tools = new ToolRegistry().register(new ChattyTool(3));
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"chatty\",\"arguments\":{}}");
        for (int i = 0; i < 3; i++) {
            stub.enqueuePlain("ответ инструменту");
        }
        stub.enqueuePlain("Итог.");

        AgentReply reply = core(tools, 15).handle(ask("сделай"));

        assertEquals("Итог.", reply.asPlainText());

        assertEquals(5, stub.callCount());
    }

    @Test
    void budgetCutsOffChattyToolAndConversationContinuesWithoutIt() {
        ToolRegistry tools = new ToolRegistry().register(new ChattyTool(50));
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"chatty\",\"arguments\":{}}");
        stub.enqueuePlain("ответ инструменту 1");
        stub.enqueuePlain("ответ инструменту 2");
        stub.enqueuePlain("Насколько помню, это было так-то; проверить сейчас не получилось.");

        AgentReply reply = core(tools, 4).handle(ask("а что там с этим?"));

        assertEquals("Насколько помню, это было так-то; проверить сейчас не получилось.", reply.asPlainText());
        assertEquals(4, stub.callCount(), "the shared cap holds calls from inside the tool too");

        String fallback = stub.requests().get(3).toString();
        assertTrue(fallback.contains("did not finish"), "the answer follows the 'without tool' prompt: " + fallback);
        assertTrue(fallback.contains("Do not mention limits"), fallback);
        assertTrue(stub.requests().get(3).path("format").isMissingNode(), "free text, no schema");
        assertTrue(!reply.asPlainText().contains("Не справился"), "no service error shown to the user");
    }

    @Test
    void budgetOfOneLeavesToolNoCalls() {
        ToolRegistry tools = new ToolRegistry().register(new ChattyTool(1));
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"chatty\",\"arguments\":{}}");
        stub.enqueuePlain("Отвечаю сам.");

        AgentReply reply = core(tools, 2).handle(ask("сделай"));

        assertEquals("Отвечаю сам.", reply.asPlainText());
        assertEquals(2, stub.callCount(), "decision + answer without tool; the tool got nothing");
    }

    @Test
    void unknownToolDoesNotBreakLoop() {
        stub.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"nope\",\"arguments\":{}}");
        stub.enqueuePlain("Такого инструмента нет.");

        AgentReply reply = core(new ToolRegistry().register(new GetCurrentTimeTool()), 15)
                .handle(ask("сделай"));

        assertEquals("Такого инструмента нет.", reply.asPlainText());
        assertTrue(stub.requests().get(1).toString().contains("No such tool"),
                "the model must see the error and explain it");
    }

    @Test
    void toolsAreDescribedInPromptAndSchema() {
        ToolRegistry tools = new ToolRegistry().register(new GetCurrentTimeTool());
        stub.enqueueReply("ок");

        core(tools, 15).handle(ask("привет"));

        String request = stub.requests().getFirst().toString();
        assertTrue(request.contains("get_current_time"));
        assertTrue(request.contains("tool_call"));
    }

    @Test
    void freshnessHintIsAddedOnlyWithWebSearch() {
        stub.enqueueReply("ок");
        stub.enqueueReply("ок");

        core(new ToolRegistry().register(new GetCurrentTimeTool()), 15).handle(ask("какой курс доллара?"));
        assertTrue(!stub.requests().getFirst().toString().contains("Hint: the question seems"),
                "nothing to hint without web_search");

        Tool fakeSearch = new Tool() {
            public String name() { return "web_search"; }
            public String description() { return ""; }
            public Map<String, Object> parameters() { return Map.of(); }
            public ToolResult execute(Map<String, Object> a, ToolContext c) { return ToolResult.ok(""); }
        };
        core(new ToolRegistry().register(fakeSearch), 15).handle(ask("какой курс доллара?"));
        assertTrue(stub.requests().get(1).toString().contains("Hint: the question seems"));
    }
}
