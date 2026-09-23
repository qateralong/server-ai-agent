package com.bebebe.agent.core;

import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmRequest;
import com.bebebe.agent.llm.LlmResponse;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.ollama.OllamaStats;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fact used to reach the prompt only when the name of the person it is about literally
 * appeared in the message. Everything else stayed in the database, which looked from the
 * outside exactly like the agent having forgotten it.
 */
class MemoryRecallTest {

    @TempDir
    Path temp;

    private MemoryStore memory;
    private ScriptLibrary library;
    private final List<LlmRequest> seen = new ArrayList<>();

    private final LlmProvider stub = new LlmProvider() {
        public String id() { return "stub"; }
        public String displayName() { return "Stub"; }
        public String model() { return "stub"; }
        public void setModel(String model) { }
        public void setApiKey(String apiKey) { }
        public String endpoint() { return "stub://"; }
        public Duration timeout() { return Duration.ofSeconds(5); }
        public List<String> listModels() { return List.of("stub"); }
        public boolean ping() { return true; }
        public OllamaStats stats() { return new OllamaStats(); }
        public void close() { }

        @Override
        public LlmResponse chat(LlmRequest request) {
            seen.add(request);
            return new LlmResponse("{\"type\":\"reply\",\"reply\":\"ок\"}", 1, 1, 1_000_000L, "stub");
        }
    };

    @BeforeEach
    void setUp() {
        memory = TestMemory.inDirectory(temp, 1000);
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("library.db"), temp.resolve("lib")))
                .section(LibraryConfig.SECTION)));
    }

    @AfterEach
    void tearDown() {
        memory.close();
        library.close();
    }

    private AgentCore core() {
        return new AgentCore(new AgentSwitch(true), stub, TestActions.NONE, library, memory,
                new ToolRegistry(), 15);
    }

    private String promptOfLastCall() {
        LlmRequest last = seen.getLast();
        return last.system() + "\n" + last.user();
    }

    @Test
    void aFactIsRecalledByTheWordsOfTheQuestionEvenWithoutTheName() {
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");
        memory.addFact("Саша вегетарианец, не ест мясо", FactCategory.PREFERENCE, null, null,
                List.of(sasha.id()));

        core().handle(UserMessage.telegram("кто из моих знакомых вегетарианец?", 1L));

        assertTrue(promptOfLastCall().contains("вегетарианец"),
                "the fact is stored and the question is about it -- it must reach the prompt");
    }

    @Test
    void anUnrelatedQuestionDoesNotDragFactsIn() {
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");
        memory.addFact("Саша вегетарианец", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));

        core().handle(UserMessage.telegram("сколько будет два плюс два?", 1L));

        assertFalse(promptOfLastCall().contains("вегетарианец"),
                "recall must answer the message, not pad the prompt with whatever is recent");
    }

    @Test
    void aFactAboutANamedPersonIsNotListedTwice() {
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");
        memory.addFact("Саша вегетарианец", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));

        core().handle(UserMessage.telegram("что там с Сашей и вегетарианством?", 1L));

        String prompt = promptOfLastCall();
        int first = prompt.indexOf("Саша вегетарианец");
        assertTrue(first >= 0);
        assertTrue(prompt.indexOf("Саша вегетарианец", first + 1) < 0,
                "already shown as a fact of the mentioned person -- recall must not repeat it");
    }
}
