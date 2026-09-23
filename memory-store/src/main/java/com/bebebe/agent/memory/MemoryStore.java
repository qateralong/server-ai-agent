package com.bebebe.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class MemoryStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final MemoryConfig config;
    private final Connection connection;

    public MemoryStore(MemoryConfig config) {
        this.config = config;
        try {
            if (config.dbPath().getParent() != null) {
                Files.createDirectories(config.dbPath().getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + config.dbPath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            migrate();
            log.info("Memory: {} (entities {}, facts {})", config.dbPath(), countEntities(), countFacts());
        } catch (IOException | SQLException e) {
            throw new MemoryException("Cannot open memory database: " + config.dbPath(), e);
        }
    }

    public MemoryConfig config() {
        return config;
    }

    private void migrate() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        conversation_key  TEXT    NOT NULL,
                        started_at        TEXT    NOT NULL,
                        last_message_at   TEXT    NOT NULL,
                        ended_at          TEXT,
                        consolidated_up_to INTEGER NOT NULL DEFAULT 0
                    )""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_key ON sessions(conversation_key, ended_at)");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id  INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        role        TEXT    NOT NULL,
                        text        TEXT    NOT NULL,
                        at          TEXT    NOT NULL,
                        source      TEXT    NOT NULL DEFAULT '',
                        trace_id    TEXT
                    )""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, id)");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS entities (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        canonical_name TEXT NOT NULL,
                        aliases        TEXT NOT NULL DEFAULT '',
                        relation       TEXT NOT NULL DEFAULT '',
                        notes          TEXT NOT NULL DEFAULT '',
                        created_at     TEXT NOT NULL
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS facts (
                        id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        text              TEXT NOT NULL,
                        category          TEXT NOT NULL,
                        fact_date         TEXT,
                        source_message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
                        created_at        TEXT NOT NULL
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS fact_entities (
                        fact_id   INTEGER NOT NULL REFERENCES facts(id) ON DELETE CASCADE,
                        entity_id INTEGER NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
                        PRIMARY KEY (fact_id, entity_id)
                    )""");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fact_entities_entity ON fact_entities(entity_id)");
        }
    }

    public synchronized DialogSession openOrContinue(String conversationKey) {
        Optional<DialogSession> active = activeSession(conversationKey);
        if (active.isPresent()) {
            DialogSession session = active.get();
            Instant deadline = session.lastMessageAt().plus(config.sessionIdle());
            if (Instant.now().isBefore(deadline)) {
                return session;
            }
            endSession(session.id());
            log.info("Session {} closed after idle ({})", session.id(), conversationKey);
        }
        return startSession(conversationKey);
    }

    public synchronized Optional<DialogSession> activeSession(String conversationKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE conversation_key = ? AND ended_at IS NULL ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, conversationKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot read session " + conversationKey, e);
        }
    }

    public synchronized List<DialogSession> activeSessions() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY id")) {
            return readSessions(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read active sessions", e);
        }
    }

    public synchronized Optional<DialogSession> session(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM sessions WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot read session " + id, e);
        }
    }

    private DialogSession startSession(String conversationKey) {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions (conversation_key, started_at, last_message_at) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, conversationKey);
            ps.setString(2, now.toString());
            ps.setString(3, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                log.info("New dialog session {} ({})", id, conversationKey);
                return new DialogSession(id, conversationKey, now, now, null, 0);
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot open session", e);
        }
    }

    public synchronized void endSession(long sessionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET ended_at = ? WHERE id = ? AND ended_at IS NULL")) {
            ps.setString(1, Instant.now().toString());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MemoryException("Cannot close session " + sessionId, e);
        }
    }

    public synchronized void markConsolidated(long sessionId, long upToMessageId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET consolidated_up_to = ? WHERE id = ?")) {
            ps.setLong(1, upToMessageId);
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MemoryException("Cannot mark consolidation of session " + sessionId, e);
        }
    }

    public synchronized DialogMessage append(long sessionId, MessageRole role, String text,
                                             String source, String traceId) {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO messages (session_id, role, text, at, source, trace_id) VALUES (?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sessionId);
            ps.setString(2, role.wireName());
            ps.setString(3, text == null ? "" : text);
            ps.setString(4, now.toString());
            ps.setString(5, source == null ? "" : source);
            ps.setString(6, traceId);
            ps.executeUpdate();
            try (PreparedStatement touch = connection.prepareStatement(
                    "UPDATE sessions SET last_message_at = ? WHERE id = ?")) {
                touch.setString(1, now.toString());
                touch.setLong(2, sessionId);
                touch.executeUpdate();
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new DialogMessage(keys.getLong(1), sessionId, role, text, now, source, traceId);
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot write message", e);
        }
    }

    public synchronized List<DialogMessage> messages(long sessionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM messages WHERE session_id = ? ORDER BY id")) {
            ps.setLong(1, sessionId);
            return readMessages(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read messages of session " + sessionId, e);
        }
    }

    public synchronized List<DialogMessage> unconsolidated(DialogSession session) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM messages WHERE session_id = ? AND id > ? ORDER BY id")) {
            ps.setLong(1, session.id());
            ps.setLong(2, session.consolidatedUpTo());
            return readMessages(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read tail of session " + session.id(), e);
        }
    }

    public synchronized Entity addEntity(String canonicalName, List<String> aliases, String relation, String notes) {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO entities (canonical_name, aliases, relation, notes, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, canonicalName.strip());
            ps.setString(2, joinAliases(aliases));
            ps.setString(3, relation == null ? "" : relation.strip());
            ps.setString(4, notes == null ? "" : notes.strip());
            ps.setString(5, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                Entity entity = entity(keys.getLong(1)).orElseThrow();
                log.info("New entity: {}", entity.describeForModel());
                return entity;
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot add entity " + canonicalName, e);
        }
    }

    public synchronized Entity enrichEntity(long id, List<String> newAliases, String relation) {
        Entity current = entity(id).orElseThrow(() -> new MemoryException("No entity " + id));
        List<String> aliases = new ArrayList<>(current.aliases());
        for (String alias : newAliases == null ? List.<String>of() : newAliases) {
            String candidate = alias.strip();
            boolean known = candidate.isEmpty()
                    || candidate.equalsIgnoreCase(current.canonicalName())
                    || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(candidate));
            if (!known) {
                aliases.add(candidate);
            }
        }
        String newRelation = current.relation().isEmpty() && relation != null ? relation.strip() : current.relation();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE entities SET aliases = ?, relation = ? WHERE id = ?")) {
            ps.setString(1, joinAliases(aliases));
            ps.setString(2, newRelation);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MemoryException("Cannot update entity " + id, e);
        }
        return entity(id).orElseThrow();
    }

    public synchronized Optional<Entity> entity(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM entities WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readEntity(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot read entity " + id, e);
        }
    }

    public synchronized List<Entity> entities() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM entities ORDER BY canonical_name COLLATE NOCASE")) {
            List<Entity> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readEntity(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new MemoryException("Cannot read entities", e);
        }
    }

    public synchronized Optional<Entity> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = name.strip().toLowerCase(java.util.Locale.ROOT);
        return entities().stream()
                .filter(e -> e.allNamesLower().contains(needle))
                .findFirst();
    }

    public synchronized boolean deleteEntity(long id) {
        try {

            List<Long> touched = factsOf(id).stream().map(Fact::id).toList();
            int deleted;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM entities WHERE id = ?")) {
                ps.setLong(1, id);
                deleted = ps.executeUpdate();
            }
            if (deleted == 0) {
                return false;
            }
            int orphaned = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM facts WHERE id = ? AND id NOT IN (SELECT fact_id FROM fact_entities)")) {
                for (Long factId : touched) {
                    ps.setLong(1, factId);
                    orphaned += ps.executeUpdate();
                }
            }
            log.info("Entity {} deleted together with {} facts about it only", id, orphaned);
            return true;
        } catch (SQLException e) {
            throw new MemoryException("Cannot delete entity " + id, e);
        }
    }

    public synchronized Fact addFact(String text, FactCategory category, LocalDate date,
                                     Long sourceMessageId, List<Long> entityIds) {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO facts (text, category, fact_date, source_message_id, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, text.strip());
            ps.setString(2, category.wireName());
            ps.setString(3, date == null ? null : date.toString());
            if (sourceMessageId == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, sourceMessageId);
            }
            ps.setString(5, now.toString());
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
            try (PreparedStatement link = connection.prepareStatement(
                    "INSERT OR IGNORE INTO fact_entities (fact_id, entity_id) VALUES (?, ?)")) {
                for (Long entityId : entityIds == null ? List.<Long>of() : entityIds) {
                    link.setLong(1, id);
                    link.setLong(2, entityId);
                    link.addBatch();
                }
                link.executeBatch();
            }
            Fact fact = fact(id).orElseThrow();
            log.atInfo()
                    .addKeyValue("event", "memory.fact")
                    .addKeyValue("fact_id", id)
                    .addKeyValue("category", category.wireName())
                    .addKeyValue("entities", entityIds == null ? "" : entityIds.toString())
                    .log("Fact remembered: {}", fact.text());
            return fact;
        } catch (SQLException e) {
            throw new MemoryException("Cannot write fact", e);
        }
    }

    public synchronized Optional<Fact> fact(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM facts WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readFact(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot read fact " + id, e);
        }
    }

    public synchronized List<Fact> factsOf(long entityId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT f.* FROM facts f
                JOIN fact_entities fe ON fe.fact_id = f.id
                WHERE fe.entity_id = ?
                ORDER BY f.fact_date IS NULL, f.fact_date DESC, f.id DESC""")) {
            ps.setLong(1, entityId);
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read facts of entity " + entityId, e);
        }
    }

    public synchronized List<Fact> factsAboutUser() {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT f.* FROM facts f
                WHERE f.id NOT IN (SELECT fact_id FROM fact_entities)
                ORDER BY f.id DESC""")) {
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read facts about the user", e);
        }
    }

    /**
     * Everything remembered, newest first, capped.
     *
     * <p>For recall by keyword: a fact only reached the prompt if the name of the person it is
     * about literally appeared in the message, so "кто из знакомых вегетарианец?" found nothing
     * even with the fact stored. Ranking happens in the caller; this only hands over the pile.
     */
    public synchronized List<Fact> allFacts(int limit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM facts ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read the facts", e);
        }
    }

    public synchronized List<Fact> factsByCategory(FactCategory category) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM facts WHERE category = ? ORDER BY id DESC")) {
            ps.setString(1, category.wireName());
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read facts of category " + category, e);
        }
    }

    public synchronized boolean deleteFact(long id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM facts WHERE id = ?")) {
            ps.setLong(1, id);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted) {
                log.info("Fact {} deleted", id);
            }
            return deleted;
        } catch (SQLException e) {
            throw new MemoryException("Cannot delete fact " + id, e);
        }
    }

    public synchronized int forgetActiveSession(String conversationKey) {
        Optional<DialogSession> active = activeSession(conversationKey);
        if (active.isEmpty()) {
            return 0;
        }
        int messages = messages(active.get().id()).size();
        deleteSessions("id = " + active.get().id());
        return messages;
    }

    public synchronized int forgetAllSessions() {
        int messages = countMessages();
        deleteSessions("1 = 1");
        return messages;
    }

    public synchronized int forgetAllFacts() {

        int n = countFacts();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("DELETE FROM facts");
            log.warn("All facts deleted: {}", n);
            return n;
        } catch (SQLException e) {
            throw new MemoryException("Cannot clear facts", e);
        }
    }

    public synchronized void forgetEverything() {
        forgetAllSessions();
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("DELETE FROM facts");
            s.executeUpdate("DELETE FROM entities");
            log.warn("Memory completely cleared");
        } catch (SQLException e) {
            throw new MemoryException("Cannot clear memory", e);
        }
    }

    private void deleteSessions(String where) {
        try (Statement s = connection.createStatement()) {

            s.executeUpdate("DELETE FROM sessions WHERE " + where);
            log.info("Sessions deleted ({})", where);
        } catch (SQLException e) {
            throw new MemoryException("Cannot delete sessions", e);
        }
    }

    public synchronized int countEntities() {
        return count("entities");
    }

    public synchronized int countFacts() {
        return count("facts");
    }

    public synchronized int countMessages() {
        return count("messages");
    }

    private int count(String table) {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MemoryException("Cannot count " + table, e);
        }
    }

    private static DialogSession readSession(ResultSet rs) throws SQLException {
        String ended = rs.getString("ended_at");
        return new DialogSession(
                rs.getLong("id"),
                rs.getString("conversation_key"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("last_message_at")),
                ended == null ? null : Instant.parse(ended),
                rs.getLong("consolidated_up_to"));
    }

    private List<DialogSession> readSessions(PreparedStatement ps) throws SQLException {
        List<DialogSession> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readSession(rs));
            }
        }
        return list;
    }

    private List<DialogMessage> readMessages(PreparedStatement ps) throws SQLException {
        List<DialogMessage> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new DialogMessage(
                        rs.getLong("id"),
                        rs.getLong("session_id"),
                        MessageRole.fromWire(rs.getString("role")),
                        rs.getString("text"),
                        Instant.parse(rs.getString("at")),
                        rs.getString("source"),
                        rs.getString("trace_id")));
            }
        }
        return list;
    }

    private static Entity readEntity(ResultSet rs) throws SQLException {
        return new Entity(
                rs.getLong("id"),
                rs.getString("canonical_name"),
                splitAliases(rs.getString("aliases")),
                rs.getString("relation"),
                rs.getString("notes"),
                Instant.parse(rs.getString("created_at")));
    }

    private Fact readFact(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String date = rs.getString("fact_date");
        long source = rs.getLong("source_message_id");
        boolean sourceNull = rs.wasNull();
        return new Fact(
                id,
                rs.getString("text"),
                FactCategory.fromWire(rs.getString("category")),
                date == null ? null : LocalDate.parse(date),
                sourceNull ? null : source,
                entityIdsOf(id),
                Instant.parse(rs.getString("created_at")));
    }

    private List<Fact> readFacts(PreparedStatement ps) throws SQLException {
        List<Fact> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readFact(rs));
            }
        }
        return list;
    }

    private List<Long> entityIdsOf(long factId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entity_id FROM fact_entities WHERE fact_id = ? ORDER BY entity_id")) {
            ps.setLong(1, factId);
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            return ids;
        }
    }

    private static String joinAliases(List<String> aliases) {
        if (aliases == null) {
            return "";
        }
        return aliases.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct()
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static List<String> splitAliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Cannot close memory database", e);
        }
    }

    public static class MemoryException extends RuntimeException {
        public MemoryException(String message) {
            super(message);
        }

        public MemoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
