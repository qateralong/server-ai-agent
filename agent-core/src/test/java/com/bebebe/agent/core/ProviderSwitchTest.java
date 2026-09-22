package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.OllamaProvider;
import com.bebebe.agent.llm.SwitchableProvider;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderSwitchTest {

    @TempDir
    Path temp;

    private OllamaStubServer first;
    private OllamaStubServer second;
    private LlmProvider providerA;
    private LlmProvider providerB;
    private SwitchableProvider llm;
    private ScriptLibrary library;
    private MemoryStore memory;
    private AgentCore core;

    private final Tool switcher = new Tool() {
        public String name() { return "switch_provider"; }
        public String description() { return "switches"; }
        public Map<String, Object> parameters() { return Map.of(); }
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            llm.switchTo("b");
            return ToolResult.ok("switched");
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        first = new OllamaStubServer();
        second = new OllamaStubServer();
        providerA = new OllamaProvider(new OllamaClient(new OllamaConfig(first.baseUrl(), "", "a", Duration.ofSeconds(30), null, null)));
        providerB = new OllamaProvider(new OllamaClient(new OllamaConfig(second.baseUrl(), "", "b", Duration.ofSeconds(30), null, null)));
        llm = new SwitchableProvider(providerA, id -> id.equals("b") ? providerB : providerA);
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
        core = new AgentCore(new AgentSwitch(true), llm, scripts, library, memory,
                new ToolRegistry().register(switcher), null, Clock.systemDefaultZone(), 15);
    }

    @AfterEach
    void tearDown() {
        memory.close();
        library.close();
        providerA.close();
        providerB.close();
        first.close();
        second.close();
    }

    @Test
    void requestFinishesOnOldProviderAndNextGoesToNew() {
        first.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"switch_provider\",\"arguments\":{}}");
        first.enqueuePlain("Готово на A.");
        second.enqueueReply("Привет от B.");

        AgentReply during = core.handle(UserMessage.telegram("переключи", 1L));
        AgentReply after = core.handle(UserMessage.telegram("привет", 1L));

        assertEquals("Готово на A.", during.asPlainText(), "the loop finished on the old provider");
        assertEquals(2, first.callCount(), "decision and formulation -- both on A");
        assertEquals("Привет от B.", after.asPlainText());
        assertEquals(1, second.callCount(), "new request -- on B");
        assertEquals(providerB, llm.pin());
    }

    @Test
    void modelAndKeyChangeOnlyForCurrent() {
        llm.setModel("x-model");
        assertEquals("x-model", providerA.model());
        assertEquals("b", providerB.model());
    }
}
