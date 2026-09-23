package com.bebebe.agent.memory;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to a fact and to a conversation after they are written: replacement, confirmation,
 * use, and the few lines a finished session leaves behind.
 */
class MemoryLifecycleTest {

    @TempDir
    Path temp;

    private MemoryStore open() {
        return new MemoryStore(MemoryConfig.from(AppConfig.fromToml("""
                [memory]
                db_path = "%s"
                """.formatted(temp.resolve("memory.db"))).section(MemoryConfig.SECTION)));
    }

    /**
     * An agent that has been running for months has a database written by the previous schema.
     * Upgrading must add to it, not ask for it to be thrown away.
     */
    @Test
    void anOlderDatabaseIsUpgradedInPlace() throws SQLException {
        Path db = temp.resolve("memory.db");
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = raw.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        conversation_key TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        last_message_at TEXT NOT NULL,
                        ended_at TEXT,
                        consolidated_up_to INTEGER NOT NULL DEFAULT 0)""");
            s.executeUpdate("""
                    CREATE TABLE facts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        text TEXT NOT NULL,
                        category TEXT NOT NULL,
                        fact_date TEXT,
                        source_message_id INTEGER,
                        created_at TEXT NOT NULL)""");
            s.executeUpdate("INSERT INTO facts (text, category, created_at) "
                    + "VALUES ('Пользователь живёт в Казани', 'trait', '2026-01-01T00:00:00Z')");
        }

        try (MemoryStore store = open()) {
            List<Fact> facts = store.factsAboutUser();

            assertEquals(1, facts.size(), "the old fact survived the upgrade");
            assertTrue(facts.getFirst().isCurrent());
            assertEquals(1, facts.getFirst().mentionCount(), "and got sensible defaults");
            assertEquals(0, facts.getFirst().usedCount());
            assertEquals("", store.session(1).map(DialogSession::summary).orElse(""));
        }
    }

    @Test
    void aSupersededFactLeavesTheAnswersButNotTheHistory() {
        try (MemoryStore store = open()) {
            Fact kazan = store.addFact("Пользователь живёт в Казани", FactCategory.TRAIT, null, null, List.of());
            Fact moscow = store.addFact("Пользователь живёт в Москве", FactCategory.TRAIT, null, null, List.of());

            assertTrue(store.supersede(kazan.id(), moscow.id(), "переехал"));

            assertEquals(List.of("Пользователь живёт в Москве"),
                    store.factsAboutUser().stream().map(Fact::text).toList());
            assertEquals(1, store.countFacts(), "counts what is believed, not what is stored");
            assertEquals(List.of(kazan.id()), store.supersededFacts(10).stream().map(Fact::id).toList());
            assertEquals(moscow.id(), store.fact(kazan.id()).orElseThrow().supersededBy());
        }
    }

    @Test
    void supersedingTwiceChangesNothing() {
        try (MemoryStore store = open()) {
            Fact fact = store.addFact("Пользователь курит", FactCategory.TRAIT, null, null, List.of());

            assertTrue(store.supersede(fact.id(), null, "бросил"));
            assertFalse(store.supersede(fact.id(), null, "снова"),
                    "the moment it stopped being true is not something to overwrite");
        }
    }

    @Test
    void aFactCannotReplaceItself() {
        try (MemoryStore store = open()) {
            Fact fact = store.addFact("Пользователь пьёт чай", FactCategory.PREFERENCE, null, null, List.of());

            assertFalse(store.supersede(fact.id(), fact.id(), "сам себя"));
            assertTrue(store.fact(fact.id()).orElseThrow().isCurrent());
        }
    }

    @Test
    void usingAndConfirmingAFactIsCounted() {
        try (MemoryStore store = open()) {
            Fact fact = store.addFact("Пользователь пьёт чай", FactCategory.PREFERENCE, null, null, List.of());

            assertEquals(1, store.markUsed(List.of(fact.id(), 9999L)), "unknown ids change nothing");
            store.markUsed(List.of(fact.id()));
            store.confirmFact(fact.id());

            Fact fresh = store.fact(fact.id()).orElseThrow();
            assertEquals(2, fresh.usedCount());
            assertEquals(2, fresh.mentionCount());
            assertTrue(fresh.lastUsedAt() != null);
        }
    }

    @Test
    void aClosedSessionKeepsItsSummaryAndIsOfferedBack() {
        try (MemoryStore store = open()) {
            DialogSession session = store.openOrContinue("TELEGRAM:1");
            store.append(session.id(), MessageRole.USER, "привет", "TELEGRAM", "t");
            store.saveSummary(session.id(), "Обсуждали настройку whisper.");
            store.endSession(session.id());

            List<DialogSession> recent = store.recentSummaries(5);

            assertEquals(1, recent.size());
            assertEquals("Обсуждали настройку whisper.", recent.getFirst().summary());
            assertFalse(recent.getFirst().isActive());
        }
    }

    @Test
    void aSessionWithoutASummaryIsNotOfferedAsAnEpisode() {
        try (MemoryStore store = open()) {
            DialogSession session = store.openOrContinue("TELEGRAM:1");
            store.append(session.id(), MessageRole.USER, "привет", "TELEGRAM", "t");
            store.endSession(session.id());

            assertTrue(store.recentSummaries(5).isEmpty(),
                    "an empty summary is not an episode, it is a session nobody summarised");
        }
    }

    /**
     * Closing is where a conversation has to be turned into something that outlives it, and the
     * store cannot do that itself -- it knows nothing about the model.
     */
    @Test
    void closingASessionTellsWhoeverIsListening() {
        try (MemoryStore store = open()) {
            List<Long> closed = new CopyOnWriteArrayList<>();
            store.setSessionClosedListener(session -> closed.add(session.id()));

            DialogSession session = store.openOrContinue("TELEGRAM:1");
            store.append(session.id(), MessageRole.USER, "привет", "TELEGRAM", "t");
            store.endSession(session.id());
            store.endSession(session.id());

            assertEquals(List.of(session.id()), closed,
                    "closing an already closed session must not summarise it a second time");
        }
    }

    @Test
    void anIdleSessionIsClosedAndReported() throws SQLException {
        MemoryStore store = new MemoryStore(MemoryConfig.from(AppConfig.fromToml("""
                [memory]
                db_path = "%s"
                session_idle_minutes = 1
                """.formatted(temp.resolve("idle.db"))).section(MemoryConfig.SECTION)));
        try (store) {
            List<Long> closed = new ArrayList<>();
            store.setSessionClosedListener(session -> closed.add(session.id()));

            DialogSession first = store.openOrContinue("TELEGRAM:1");
            store.append(first.id(), MessageRole.USER, "привет", "TELEGRAM", "t");

            // Pretend the message is older than the idle window: the next message starts a new
            // conversation, and the old one must not take its unfinished business with it.
            backdate(temp.resolve("idle.db"), first.id());
            DialogSession second = store.openOrContinue("TELEGRAM:1");

            assertFalse(second.id() == first.id(), "a new conversation started");
            assertEquals(List.of(first.id()), closed);
        }
    }
    /** Ages a session from the outside, so the store needs no method that exists only for tests. */
    private static void backdate(Path db, long sessionId) throws SQLException {
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = raw.createStatement()) {
            s.executeUpdate("UPDATE sessions SET last_message_at = '2020-01-01T00:00:00Z' WHERE id = "
                    + sessionId);
        }
    }
}
