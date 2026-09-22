package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptEntry;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.actions.ActionExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationFlowTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ScriptLibrary library;
    private MemoryStore memoryStore;
    private AgentCore core;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(
                stub.baseUrl(), "", "stub", Duration.ofSeconds(30), null, null));
        agentSwitch = new AgentSwitch(true);
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("lib.db"), temp.resolve("lib")))
                .section(LibraryConfig.SECTION)));
        memoryStore = TestMemory.inDirectory(temp, 1000);
        ActionExecutor scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 10
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("run")))
                .section(ScriptConfig.SECTION))));
        core = new AgentCore(agentSwitch, ollama, scripts, library, memoryStore);
    }

    @AfterEach
    void tearDown() {
        library.close();
        memoryStore.close();
        ollama.close();
        stub.close();
    }

    private static UserMessage ask(String text) {
        return UserMessage.telegram(text, 100L);
    }

    private void enqueueScript(String code, String name) {
        stub.enqueue("""
                {"type":"run_script","reply":"","python_code":%s,"script_name":"%s",
                 "explanation":"Посчитает и выведет результат","script_tags":["тест"]}"""
                .formatted(quote(code), name));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void newScriptDoesNotRunWithoutPermission() {
        enqueueScript("print('сделано')", "Тестовый скрипт");

        AgentReply reply = core.handle(ask("сделай что-нибудь"));

        AgentReply.NeedsConfirmation confirmation =
                assertInstanceOf(AgentReply.NeedsConfirmation.class, reply);
        assertEquals("Посчитает и выведет результат", confirmation.description());
        assertEquals("Тестовый скрипт", confirmation.script().name());

        assertEquals(0, confirmation.script().totalRuns());
        assertEquals(1, stub.callCount(), "no formulation expected -- the script was not run");
    }

    @Test
    void afterConfirmationScriptRuns() {
        enqueueScript("print('сделано')", "Тестовый скрипт");
        stub.enqueuePlain("Готово.");

        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) core.handle(ask("сделай"));
        AgentReply result = core.confirm(confirmation.token());

        assertEquals("Готово.", result.asPlainText());
        assertEquals(1, library.byId(confirmation.script().id()).orElseThrow().successCount());
    }

    @Test
    void cancelDoesNotRunScript() {
        enqueueScript("print('нельзя')", "Опасный скрипт");

        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) core.handle(ask("сделай"));
        AgentReply result = core.cancel(confirmation.token());

        assertTrue(result.asPlainText().contains("не запускался"), result.asPlainText());
        assertEquals(0, library.byId(confirmation.script().id()).orElseThrow().totalRuns());
    }

    @Test
    void confirmationIsSingleUse() {
        enqueueScript("print('раз')", "Скрипт");
        stub.enqueuePlain("Готово.");

        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) core.handle(ask("сделай"));
        core.confirm(confirmation.token());

        AgentReply again = core.confirm(confirmation.token());
        assertTrue(again.asPlainText().contains("устарел"), again.asPlainText());
        assertEquals(1, library.byId(confirmation.script().id()).orElseThrow().totalRuns());
    }

    @Test
    void confirmationDoesNotResetBudget() {

        AgentCore small = new AgentCore(agentSwitch, ollama,
                core.executor(), library, memoryStore, 2);
        enqueueScript("raise ValueError('упал')", "Падающий");
        for (int i = 0; i < 10; i++) {
            enqueueScript("raise ValueError('опять')", "Падающий");
        }

        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) small.handle(ask("сделай"));
        AgentReply result = small.confirm(confirmation.token());

        assertTrue(result.asPlainText().contains("Не справился"), result.asPlainText());
        assertEquals(2, stub.callCount(), "the budget must continue, not start over");
    }

    @Test
    void trustedScriptRunsImmediately() {
        enqueueScript("print('первый раз')", "Скрипт");
        stub.enqueuePlain("Готово раз.");

        AgentReply.NeedsConfirmation confirmation =
                (AgentReply.NeedsConfirmation) core.handle(ask("сделай"));
        core.confirm(confirmation.token());
        core.trustScript(confirmation.script().id());

        stub.enqueue("""
                {"type":"use_script","reply":"","python_code":"","script_id":%d}"""
                .formatted(confirmation.script().id()));
        stub.enqueuePlain("Готово два.");

        AgentReply second = core.handle(ask("сделай ещё раз"));

        assertInstanceOf(AgentReply.Text.class, second);
        assertEquals("Готово два.", second.asPlainText());
    }

    @Test
    void dontAskAgainButtonClearsFlag() {
        ScriptEntry entry = library.add("Скрипт", "", List.of(), "print(1)");
        assertTrue(entry.requiresConfirmation());

        AgentReply reply = core.trustScript(entry.id());

        assertFalse(library.byId(entry.id()).orElseThrow().requiresConfirmation());
        assertTrue(reply.asPlainText().contains("Больше не буду спрашивать"), reply.asPlainText());
    }

    @Test
    void readyScriptIsTakenFromCatalogWithoutGeneration() {
        ScriptEntry saved = library.add("Свободное место", "Место на диске",
                List.of("диск"), "print('100 ГБ')");
        library.setRequiresConfirmation(saved.id(), false);

        stub.enqueue("""
                {"type":"use_script","reply":"","python_code":"","script_id":%d}"""
                .formatted(saved.id()));
        stub.enqueuePlain("Свободно 100 ГБ.");

        AgentReply reply = core.handle(ask("сколько места на диске?"));

        assertEquals("Свободно 100 ГБ.", reply.asPlainText());

        assertEquals(1, library.latestVersions().size());
    }

    @Test
    void catalogCandidatesGetIntoPrompt() {
        ScriptEntry saved = library.add("Свободное место на диске", "Показывает гигабайты",
                List.of("диск", "место"), "print(1)");
        stub.enqueueReply("не нужно");

        core.handle(ask("сколько свободного места на диске?"));

        String request = stub.requests().getFirst().toString();
        assertTrue(request.contains("[" + saved.id() + "]"), "candidate id not in the prompt");
        assertTrue(request.contains("use_script"), "the model was not told how to pick a ready one");
    }

    @Test
    void unrelatedRequestPullsNoCandidates() {
        library.add("Свободное место на диске", "гигабайты", List.of("диск"), "print(1)");
        stub.enqueueReply("Париж");

        core.handle(ask("столица Франции"));

        assertFalse(stub.requests().getFirst().toString().contains("Ready scripts"),
                "must not offer scripts for a general-knowledge question");
    }

    @Test
    void complaintBecomesNewVersionOfPreviousScript() {

        enqueueScript("print('42')", "Счётчик");
        stub.enqueuePlain("Получилось 42.");
        AgentReply.NeedsConfirmation first =
                (AgentReply.NeedsConfirmation) core.handle(ask("посчитай"));
        core.confirm(first.token());
        long rootId = first.script().rootId();

        stub.enqueue("""
                {"type":"fix_last_script","reply":"","python_code":"print('43')"}""");
        stub.enqueuePlain("Теперь 43.");

        AgentReply reply = core.handle(ask("неправильно, должно быть на один больше"));
        if (reply instanceof AgentReply.NeedsConfirmation confirmation) {
            reply = core.confirm(confirmation.token());
        }

        assertEquals("Теперь 43.", reply.asPlainText());
        List<ScriptEntry> versions = library.allVersionsOf(rootId);
        assertEquals(2, versions.size(), "new version not created");
        assertEquals(2, versions.getLast().version());
        assertEquals("print('43')", library.codeOf(versions.getLast()).orElseThrow());
    }

    @Test
    void previousScriptIsShownToModelInNextRequest() {
        enqueueScript("print('x')", "Скрипт");
        stub.enqueuePlain("Готово.");
        AgentReply.NeedsConfirmation first =
                (AgentReply.NeedsConfirmation) core.handle(ask("сделай"));
        core.confirm(first.token());

        stub.enqueueReply("ладно");
        core.handle(ask("не то"));

        String secondRequest = stub.requests().getLast().toString();
        assertTrue(secondRequest.contains("Last executed script"), secondRequest);
        assertTrue(secondRequest.contains("fix_last_script"));
    }

    @Test
    void complaintWithoutPreviousRunDoesNotBreakAgent() {

        stub.enqueue("""
                {"type":"fix_last_script","reply":"","python_code":"print('новый')"}""");
        stub.enqueuePlain("Готово.");

        AgentReply reply = core.handle(ask("почини"));

        assertInstanceOf(AgentReply.NeedsConfirmation.class, reply);
        assertEquals(1, library.latestVersions().size());
    }

    @Test
    void complaintFromAnotherChannelDoesNotTouchForeignScript() {
        enqueueScript("print('x')", "Скрипт");
        stub.enqueuePlain("Готово.");
        AgentReply.NeedsConfirmation first =
                (AgentReply.NeedsConfirmation) core.handle(ask("сделай"));
        core.confirm(first.token());

        stub.enqueueReply("ок");
        core.handle(UserMessage.voice("не то"));

        assertFalse(stub.requests().getLast().toString().contains("Last executed script"));
    }
}
