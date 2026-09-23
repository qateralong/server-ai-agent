package com.bebebe.agent.assembly;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression this file exists for: tell the agent about a new person, kill the process the
 * way a restart kills it, start again, ask about that person -- and find them.
 *
 * <p>It went wrong in two independent places at once, and both are checked here. Memory was
 * consolidated only by the ON/OFF toggle, so a {@code systemctl restart} or a closed window
 * dropped the tail of the conversation; and the extraction answer was parsed with a bare
 * {@code readTree}, so a model that wrapped its JSON in ``` fences produced nothing while the
 * messages it was given were already marked as used up.
 */
class MemoryAcrossRestartTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZONE);

    /** Deliberately fenced, the way the live model answered when memory stopped working. */
    private static final String FENCED_EXTRACTION = """
            ```json
            {"entities":[{"name":"Саша","aliases":["Александр"],"relation":"друг",
             "match":{"confidence":"none","entity_id":0}}],
             "facts":[{"text":"Саша не ест мясо","category":"preference","entities":["Саша"],"date":""}]}
            ```""";

    @TempDir
    Path temp;

    private OllamaStubServer ollama;
    private AgentAssembly app;

    @BeforeEach
    void setUp() throws IOException {
        ollama = new OllamaStubServer();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
        ollama.close();
    }

    /** No Telegram here: the point is the memory file, not the delivery. */
    private AgentAssembly boot() {
        AppConfig config = AppConfig.fromToml("""
                [agent]
                enabled_on_start = true
                request_budget = 15
                stop_grace_seconds = 5
                [ollama]
                base_url = "%s"
                api_key = ""
                model = "stub"
                timeout_seconds = 30
                [telegram]
                bot_token = ""
                [memory]
                db_path = "%s"
                consolidate_every = 1000
                [scheduler]
                db_path = "%s"
                tick_seconds = 300
                desktop_notifications = false
                [notes]
                dir = "%s"
                git_history = false
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                [scripts]
                venv_dir = "%s"
                [updates]
                enabled = false
                [watchdog]
                enabled = false
                """.formatted(ollama.baseUrl(), temp.resolve("data/memory.db"),
                temp.resolve("data/scheduler.db"), temp.resolve("notes"),
                temp.resolve("library.db"), temp.resolve("lib"), temp.resolve("venv")));
        AppSettings settings = AppSettings.from(config);
        return AgentAssembly.build(config, settings,
                new AgentAssembly.Options(ollama.baseUrl(), null, CLOCK, null, false));
    }

    private static UserMessage said(String text) {
        return new UserMessage(com.bebebe.agent.core.MessageSource.TELEGRAM, text,
                Instant.parse("2026-09-20T12:00:00Z"), "1", "trace");
    }

    @Test
    void aPersonMentionedBeforeARestartIsRememberedAfterIt() throws SQLException {

        // --- first run: the user mentions a new person -------------------------------------
        app = boot();
        ollama.enqueueReply("Запомнил.");
        ollama.enqueuePlain(FENCED_EXTRACTION);

        app.core().handle(said("мой друг Саша не ест мясо"));

        assertEquals(0, countOf("entities"), "nothing is extracted mid-conversation at this setting");

        // --- the process goes away: this is exactly what the JVM shutdown hook calls --------
        app.close();
        app = null;

        assertEquals(1, countOf("entities"), "the tail must be consolidated when the process stops");
        assertEquals(1, countOf("facts"));
        assertFalse(endedAtOfLastSession().isBlank(), "and the session must be closed, not left open");

        // --- second run: a different process, the same file ---------------------------------
        app = boot();
        ollama.enqueueReply("Что-нибудь овощное.");

        app.core().handle(said("что приготовить Саше?"));

        JsonNode decision = ollama.requests().getLast();
        String systemPrompt = decision.path("messages").get(0).path("content").asText();
        assertTrue(systemPrompt.contains("Саша"),
                "the person from the previous process must reach the prompt: " + systemPrompt);
        assertTrue(systemPrompt.contains("не ест мясо"),
                "and so must the fact about them: " + systemPrompt);
    }

    @Test
    void anAnswerThatCannotBeUnderstoodLeavesTheMessagesForTheNextTry() throws SQLException {

        app = boot();
        ollama.enqueueReply("Ок.");
        ollama.enqueuePlain("I am afraid I cannot do that.");

        app.core().handle(said("мой друг Саша не ест мясо"));
        app.close();
        app = null;

        assertEquals(0, countOf("facts"), "nothing could be extracted");
        assertEquals(0, consolidatedUpTo(),
                "and the messages must NOT be marked as used up -- burning them was the bug");
    }

    private long countOf(String table) throws SQLException {
        return queryLong("SELECT count(*) FROM " + table);
    }

    private long consolidatedUpTo() throws SQLException {
        return queryLong("SELECT max(consolidated_up_to) FROM sessions");
    }

    private String endedAtOfLastSession() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ended_at FROM sessions ORDER BY id DESC LIMIT 1")) {
            return rs.next() ? String.valueOf(rs.getString(1)) : "";
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("data/memory.db"));
    }
}
