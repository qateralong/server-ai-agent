package com.bebebe.agent.core;

import com.bebebe.agent.llm.LlmImage;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmRequest;
import com.bebebe.agent.llm.LlmResponse;
import com.bebebe.agent.ollama.OllamaStats;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A picture is context for one question: it goes with the decision call and nowhere else.
 */
class ImageContextTest {

    @TempDir
    Path temp;

    private MemoryStore memory;
    private ScriptLibrary library;
    private com.bebebe.agent.transport.actions.ActionExecutor scripts;
    private final List<LlmRequest> seen = new ArrayList<>();

    /** Answers "reply" to anything and records what it was asked. */
    private final class Stub implements LlmProvider {
        private final boolean vision;

        Stub(boolean vision) {
            this.vision = vision;
        }

        public String id() { return "stub"; }
        public String displayName() { return "Stub"; }
        public String model() { return vision ? "seeing-model" : "blind-model"; }
        public void setModel(String model) { }
        public void setApiKey(String apiKey) { }
        public String endpoint() { return "stub://"; }
        public Duration timeout() { return Duration.ofSeconds(5); }
        public List<String> listModels() { return List.of(model()); }
        public boolean ping() { return true; }
        public OllamaStats stats() { return new OllamaStats(); }
        public void close() { }

        @Override
        public boolean supportsImages() {
            return vision;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            seen.add(request);
            return new LlmResponse("{\"type\":\"reply\",\"reply\":\"Вижу ошибку NullPointerException.\"}",
                    1, 1, 1_000_000L, model());
        }
    }

    @BeforeEach
    void setUp() {
        memory = TestMemory.inDirectory(temp, 1000);
        library = new ScriptLibrary(com.bebebe.agent.script.library.LibraryConfig.from(
                com.bebebe.agent.config.AppConfig.fromToml("""
                        [library]
                        db_path = "%s"
                        scripts_dir = "%s"
                        """.formatted(temp.resolve("library.db"), temp.resolve("lib")))
                        .section(com.bebebe.agent.script.library.LibraryConfig.SECTION)));
        scripts = new com.bebebe.agent.script.runtime.LocalActionExecutor(
                new com.bebebe.agent.script.runtime.ScriptRuntime(
                        com.bebebe.agent.script.runtime.ScriptConfig.from(
                                com.bebebe.agent.config.AppConfig.fromToml("""
                                        [scripts]
                                        venv_dir = "%s"
                                        scripts_dir = "%s"
                                        timeout_seconds = 10
                                        auto_install_deps = false
                                        """.formatted(temp.resolve("venv"), temp.resolve("scripts")))
                                        .section(com.bebebe.agent.script.runtime.ScriptConfig.SECTION))));
    }

    @AfterEach
    void tearDown() {
        memory.close();
        library.close();
    }

    private AgentCore core(boolean vision) {
        return new AgentCore(new AgentSwitch(true), new Stub(vision), scripts,
                library, memory, new ToolRegistry(), 15);
    }

    private static UserMessage withImage(String caption) {
        return UserMessage.image(caption, 1L, "trace",
                List.of(new LlmImage("PNGDATA".getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/png")));
    }

    @Test
    void theImageIsHandedToTheModelWithTheQuestion() {
        AgentReply reply = core(true).handle(withImage("что тут не так?"));

        assertTrue(reply.asPlainText().contains("NullPointerException"));
        assertFalse(seen.isEmpty());
        LlmRequest decision = seen.getFirst();
        assertTrue(decision.hasImages(), "the decision call must carry the picture");
        assertEquals("image/png", decision.images().getFirst().mediaType());
        assertTrue(decision.user().contains("что тут не так?"));
    }

    @Test
    void aModelThatCannotSeeSaysSoInsteadOfIgnoringTheAttachment() {
        AgentReply reply = core(false).handle(withImage("что тут не так?"));

        String text = reply.asPlainText();
        assertTrue(text.contains("blind-model"), "the model is named, because switching it is the fix: " + text);
        assertTrue(text.contains("изображение"), text);
        assertTrue(seen.isEmpty(), "and the model is not asked at all -- the answer would be about nothing");
    }

    @Test
    void anOrdinaryMessageCarriesNoImages() {
        core(true).handle(UserMessage.telegram("просто вопрос", 1L));

        assertFalse(seen.getFirst().hasImages());
    }
}
