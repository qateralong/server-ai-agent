package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.memory.MemoryStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCoreTest {

    @TempDir
    Path temp;

    private OllamaStubServer stub;
    private OllamaClient ollama;
    private AgentSwitch agentSwitch;
    private ActionExecutor scripts;
    private ScriptLibrary library;
    private MemoryStore memoryStore;

    @BeforeEach
    void setUp() throws IOException {
        stub = new OllamaStubServer();
        ollama = new OllamaClient(new OllamaConfig(
                stub.baseUrl(), "", "stub-model", Duration.ofSeconds(30), null, null));
        agentSwitch = new AgentSwitch(true);
        scripts = new LocalActionExecutor(new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 10
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("scripts")))
                .section(ScriptConfig.SECTION))));
        library = new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("library.db"), temp.resolve("lib")))
                .section(LibraryConfig.SECTION)));
        memoryStore = TestMemory.inDirectory(temp, 1000);
    }

    @AfterEach
    void tearDown() {
        library.close();
        memoryStore.close();
        ollama.close();
        stub.close();
    }

    private AgentCore core() {
        return new AgentCore(agentSwitch, ollama, scripts, library, memoryStore);
    }

    private AgentCore core(int budget) {
        return new AgentCore(agentSwitch, ollama, scripts, library, memoryStore, budget);
    }

    private static String textOf(AgentReply reply) {
        return reply.asPlainText();
    }

    private String runThrough(AgentCore core, String question) {
        AgentReply reply = core.handle(ask(question));
        if (reply instanceof AgentReply.NeedsConfirmation confirmation) {
            reply = core.confirm(confirmation.token());
        }
        return textOf(reply);
    }

    private static UserMessage ask(String text) {
        return UserMessage.telegram(text, 1L);
    }

    @Test
    void plainReplyIsReturnedAsIs() {
        stub.enqueueReply("Париж");

        assertEquals("Париж", textOf(core().handle(ask("Столица Франции?"))));
        assertEquals(1, stub.callCount(), "a plain reply must cost a single call");
    }

    @Test
    void disabledAgentDoesNotCallTheModel() {
        agentSwitch.turnOff();

        assertInstanceOf(AgentReply.Silence.class, core().handle(ask("привет")));
        assertEquals(0, stub.callCount());
    }

    @Test
    void requestCarriesStructuredResponseSchema() {
        stub.enqueueReply("ок");
        core().handle(ask("вопрос"));

        JsonNode format = stub.requests().getFirst().path("format");
        assertFalse(format.isMissingNode(), "response schema not passed");
        assertEquals(Boolean.FALSE, format.path("additionalProperties").asBoolean(true));
        assertEquals("reply", format.path("properties").path("type").path("enum").get(0).asText());
    }

    @Test
    void withScriptsDisabledModelKnowsNothingAboutThemAndRepliesWithText() {
        AgentCore core = core();
        core.setScriptsEnabled(() -> false);
        stub.enqueueReply("Выполнение действий на компьютере сейчас отключено в настройках.");

        String answer = runThrough(core, "открой браузер");

        JsonNode request = stub.requests().getFirst();
        JsonNode props = request.path("format").path("properties");
        JsonNode types = props.path("type").path("enum");
        assertEquals(2, types.size(), "schema has only reply and tool_call: " + types);
        assertTrue(props.path("python_code").isMissingNode(), "no script fields in the schema");
        assertTrue(props.path("script_id").isMissingNode(), "no script fields in the schema");
        String system = request.path("messages").get(0).path("content").asText();
        assertFalse(system.contains("run_script"), "prompt does not mention scripts:\n" + system);
        assertFalse(system.contains("python_code"), "prompt does not mention scripts:\n" + system);
        assertTrue(system.contains("DISABLED"), system);
        assertEquals(1, stub.callCount());
        assertTrue(answer.contains("отключено"), answer);
    }

    @Test
    void withScriptsDisabledScriptSentAnywayIsNotRunAndDoesNotYieldNotUnderstood() {
        AgentCore core = core();
        core.setScriptsEnabled(() -> false);
        stub.enqueueScript("print('нельзя')");
        stub.enqueuePlain("Сейчас действия на компьютере отключены, могу только рассказать, как это сделать.");

        String answer = runThrough(core, "открой браузер");

        assertEquals(2, stub.callCount(), "decision + free answer without schema, script not run");
        assertTrue(stub.requests().get(1).path("format").isMissingNode(), "fallback answer goes without schema");
        assertFalse(answer.contains("Не разобрался"), answer);
        assertTrue(answer.contains("отключены"), answer);
        assertEquals(0, library.count(), "script did not reach the catalog");
    }

    @Test
    void withScriptsDisabledUnparseableAnswerDoesNotYieldNotUnderstood() {
        AgentCore core = core();
        core.setScriptsEnabled(() -> false);
        stub.enqueuePlain("{}");
        stub.enqueuePlain("Привет!");

        assertEquals("Привет!", textOf(core.handle(ask("привет"))));
        assertEquals(2, stub.callCount());
    }

    @Test
    void withScriptsDisabledCatalogIsNotSearched() {
        library.add("Свободно в tmp", "место в /tmp", java.util.List.of("tmp", "место"), "print(1)");
        AgentCore core = core();
        core.setScriptsEnabled(() -> false);
        stub.enqueueReply("ок");

        core.handle(ask("сколько места в tmp?"));

        String user = stub.requests().getFirst().path("messages").get(1).path("content").asText();
        assertFalse(user.contains("Ready scripts"), "catalog candidates are not injected:\n" + user);
    }

    @Test
    void runButtonAfterScriptsDisabledDoesNotRunScript() {
        boolean[] enabled = {true};
        AgentCore core = core();
        core.setScriptsEnabled(() -> enabled[0]);
        stub.enqueueScript("print('поздно')");

        AgentReply reply = core.handle(ask("сделай что-нибудь"));
        AgentReply.NeedsConfirmation confirmation = assertInstanceOf(AgentReply.NeedsConfirmation.class, reply);
        enabled[0] = false;

        String answer = textOf(core.confirm(confirmation.token()));

        assertTrue(answer.contains("отключено"), answer);
        assertEquals(1, stub.callCount(), "no answer formulation -- the script was not run");
    }

    @Test
    void successfulScriptBecomesTextReply() {
        stub.enqueueScript("print('42 файла')");
        stub.enqueuePlain("В каталоге 42 файла.");

        String answer = runThrough(core(), "сколько файлов?");

        assertEquals("В каталоге 42 файла.", answer);

        assertEquals(2, stub.callCount());
        assertTrue(stub.requests().get(1).toString().contains("42 файла"),
                "script output not passed to the model for formulation");
    }

    @Test
    void failedScriptIsSentForFix() {
        stub.enqueueScript("raise ValueError('сломано')");
        stub.enqueueScript("print('починено')");
        stub.enqueuePlain("Готово: починено.");

        String answer = runThrough(core(), "сделай что-нибудь");

        assertEquals("Готово: починено.", answer);
        assertEquals(3, stub.callCount());

        String fixRequest = stub.requests().get(1).toString();
        assertTrue(fixRequest.contains("ValueError"), "the error was not shown to the model");
        assertTrue(fixRequest.contains("сломано"));
    }

    @Test
    void modelMayGiveUpWithTextInsteadOfFix() {
        stub.enqueueScript("raise RuntimeError('никак')");
        stub.enqueueReply("Так не получится: нет доступа к этим данным.");

        String answer = runThrough(core(), "достань секрет");

        assertEquals("Так не получится: нет доступа к этим данным.", answer);
        assertEquals(2, stub.callCount());
    }

    @Test
    void endlessFixIsStoppedByBudget() {

        for (int i = 0; i < 50; i++) {
            stub.enqueueScript("raise ValueError('опять')");
        }

        String answer = runThrough(core(5), "невыполнимое");

        assertTrue(answer.contains("Не справился"), answer);
        assertTrue(answer.contains("5"), "the answer must contain the limit: " + answer);
        assertEquals(5, stub.callCount(), "the budget must cap the number of calls");
    }

    @Test
    void budgetIsPerRequestNotPerStep() {

        stub.enqueueScript("raise ValueError('раз')");
        stub.enqueueScript("raise ValueError('два')");
        stub.enqueueScript("print('ок')");
        stub.enqueuePlain("Готово.");

        assertEquals("Готово.", runThrough(core(4), "задача"));
        assertEquals(4, stub.callCount());
    }

    @Test
    void newRequestGetsFreshBudget() {
        stub.enqueueReply("первый");
        stub.enqueueReply("второй");
        AgentCore core = core(1);

        assertEquals("первый", textOf(core.handle(ask("раз"))));
        assertEquals("второй", textOf(core.handle(ask("два"))));
    }

    @Test
    void onLastStepRawOutputIsReturnedInsteadOfApology() {

        stub.enqueueScript("print('сырой результат')");

        String answer = runThrough(core(1), "посчитай");

        assertEquals("сырой результат", answer);
    }

    @Test
    void unknownDecisionDoesNotLoop() {
        stub.enqueue("{\"type\":\"нечто-новое\"}");

        String answer = textOf(core().handle(ask("вопрос")));

        assertTrue(answer.contains("Не разобрался"), answer);
        assertEquals(1, stub.callCount());
    }

    @Test
    void garbageInsteadOfJsonDoesNotCrashAgent() {
        stub.enqueue("я не умею в JSON");

        assertTrue(textOf(core().handle(ask("вопрос"))).contains("Не разобрался"));
    }

    @Test
    void unavailableModelBecomesMessage() {
        stub.close();

        String answer = textOf(core().handle(ask("вопрос")));

        assertTrue(answer.contains("Не получилось обратиться к модели"), answer);
    }

    @Test
    void emptyMessageIsIgnored() {
        assertInstanceOf(AgentReply.Silence.class, core().handle(UserMessage.telegram("   ", 1L)));
        assertEquals(0, stub.callCount());
    }
}
