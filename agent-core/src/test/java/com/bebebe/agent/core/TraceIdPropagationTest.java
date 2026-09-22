package com.bebebe.agent.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdPropagationTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private ScriptLibrary library;
    private MemoryStore memoryStore;
    private AgentCore core;

    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Logger root;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(
                stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("lib.db"), temp.resolve("lib"))).section(LibraryConfig.SECTION)));
        memoryStore = TestMemory.inDirectory(temp, 1000);
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run"))).section(ScriptConfig.SECTION))));
        core = new AgentCore(new AgentSwitch(true), ollama, scripts, library, memoryStore);

        root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.bebebe.agent");
        events.start();
        root.addAppender(events);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(events);
        library.close();
        memoryStore.close();
        ollama.close();
        stub.close();
    }

    private Set<String> traceIdsOf(String subsystemPrefix) {
        return events.list.stream()
                .filter(e -> e.getLoggerName().startsWith(subsystemPrefix))
                .map(e -> e.getMDCPropertyMap().get(TraceContext.KEY))
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    private List<String> eventsNamed(String name) {
        return events.list.stream()
                .filter(e -> e.getKeyValuePairs() != null && e.getKeyValuePairs().stream()
                        .anyMatch(kv -> kv.key.equals("event") && name.equals(kv.value)))
                .map(e -> e.getMDCPropertyMap().get(TraceContext.KEY))
                .toList();
    }

    @Test
    void oneRequestOneTraceIdAcrossSubsystems() {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":"print('ok')",
                 "script_name":"Тест","explanation":"тест","script_tags":[]}""");
        stub.enqueuePlain("Готово.");

        UserMessage message = UserMessage.telegram("сделай", 1L);
        AgentReply first = core.handle(message);
        AgentReply.NeedsConfirmation confirmation = (AgentReply.NeedsConfirmation) first;

        try (TraceContext.Scope ignored = TraceContext.open("foreign-request")) {
            LoggerFactory.getLogger("com.bebebe.agent.core.Other").info("another message");
        }

        core.confirm(confirmation.token());

        Set<String> coreIds = traceIdsOf("com.bebebe.agent.core");
        Set<String> scriptIds = traceIdsOf("com.bebebe.agent.script");

        coreIds.remove("foreign-request");
        assertEquals(Set.of(message.traceId()), coreIds,
                "the core must log everything under the message trace_id");
        assertEquals(Set.of(message.traceId()), scriptIds,
                "scripts must log under the same trace_id as the core");
    }

    @Test
    void keyEventsShareOneIdentifier() {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":"print('ok')",
                 "script_name":"Тест","explanation":"тест","script_tags":[]}""");
        stub.enqueuePlain("Готово.");

        UserMessage message = UserMessage.telegram("сделай", 1L);
        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) core.handle(message);
        core.confirm(confirmation.token());

        for (String event : List.of("request.start", "decision", "confirmation.requested",
                "confirmation.accepted", "script.audit", "script.result", "reply.ready", "request.end")) {
            List<String> ids = eventsNamed(event);
            assertFalse(ids.isEmpty(), "missing event " + event);
            assertTrue(ids.stream().allMatch(message.traceId()::equals),
                    "event " + event + " went under a foreign trace_id: " + ids);
        }
    }

    @Test
    void differentMessagesGetDifferentIdentifiers() {
        stub.enqueueReply("раз");
        stub.enqueueReply("два");

        UserMessage first = UserMessage.telegram("раз", 1L);
        UserMessage second = UserMessage.telegram("два", 1L);
        core.handle(first);
        core.handle(second);

        assertFalse(first.traceId().equals(second.traceId()));
        assertEquals(List.of(first.traceId(), second.traceId()), eventsNamed("request.start"));
    }

    @Test
    void messagePicksUpIdentifierOfOpenScope() {

        try (TraceContext.Scope ignored = TraceContext.open("from-bridge-abc")) {
            assertEquals("from-bridge-abc", UserMessage.telegram("текст", 1L).traceId());
        }
        assertEquals(12, UserMessage.telegram("текст", 1L).traceId().length());
    }

    @Test
    void voiceMessageCarriesPressIdentifier() {
        assertEquals("нажатие-123", UserMessage.voice("текст", "нажатие-123").traceId());
    }

    @Test
    void auditContainsCodeAndOutput() {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":"print('маркер-вывода')",
                 "script_name":"Тест","explanation":"тест","script_tags":[]}""");
        stub.enqueuePlain("Готово.");

        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) core.handle(UserMessage.telegram("сделай", 1L));
        core.confirm(confirmation.token());

        ILoggingEvent audit = events.list.stream()
                .filter(e -> e.getKeyValuePairs() != null && e.getKeyValuePairs().stream()
                        .anyMatch(kv -> "script.audit".equals(kv.value)))
                .findFirst().orElseThrow();

        String code = kv(audit, "code");
        String stdout = kv(audit, "stdout");
        assertTrue(code.contains("маркер-вывода"), "audit has no code: " + code);
        assertTrue(stdout.contains("маркер-вывода"), "audit has no stdout: " + stdout);
        assertEquals("0", kv(audit, "exit_code"));
    }

    private static String kv(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value))
                .findFirst().orElse("");
    }
}
