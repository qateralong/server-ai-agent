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

            // Added later, so they are added to whatever is already on disk rather than to the
            // CREATE above: an existing memory must survive the upgrade untouched.
            addColumn(s, "facts", "superseded_by", "INTEGER");
            addColumn(s, "facts", "superseded_at", "TEXT");
            addColumn(s, "facts", "mention_count", "INTEGER NOT NULL DEFAULT 1");
            addColumn(s, "facts", "used_count", "INTEGER NOT NULL DEFAULT 0");
            addColumn(s, "facts", "last_used_at", "TEXT");
            addColumn(s, "facts", "source", "TEXT NOT NULL DEFAULT 'extracted'");
            addColumn(s, "facts", "keywords", "TEXT NOT NULL DEFAULT ''");
            addColumn(s, "sessions", "summary", "TEXT NOT NULL DEFAULT ''");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_facts_current ON facts(superseded_at, id)");
        }
    }

    /** SQLite has no ADD COLUMN IF NOT EXISTS, so the existing columns are read first. */
    private void addColumn(Statement s, String table, String column, String type) throws SQLException {
        try (ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("Memory schema: column {}.{} added", table, column);
    }

    /**
     * Told about every session that has just been closed -- by idle, by the switch-off hook or by
     * hand. It exists because closing is where a conversation has to be turned into something that
     * outlives it (its tail consolidated, its summary written), and that needs a model, which this
     * module knows nothing about.
     *
     * <p>Called while the store is locked, so an implementation must hand the work to another
     * thread and return.
     */
    private volatile java.util.function.Consumer<DialogSession> sessionClosed = session -> { };

    public void setSessionClosedListener(java.util.function.Consumer<DialogSession> listener) {
        this.sessionClosed = listener == null ? session -> { } : listener;
    }

    /**
     * The conversation the user is having, whatever they are having it through.
     *
     * <p>Sessions used to be per channel: asking by voice and following up by text produced two
     * separate conversations with separate logs, although the agent answers both into the same
     * Telegram chat and the user sees one stream. The agent then failed to understand "а теперь
     * на английский" about something said out loud a minute earlier.
     *
     * <p>This is allowed because the whole system is single-user by design ({@code 1.7}): there is
     * one person talking, and the channel is transport, not an interlocutor. The session's
     * {@code conversation_key} follows the channel the user last spoke through, so anything sent
     * back on its own initiative -- the "same person?" question, a consolidation digest -- goes
     * where they actually are.
     */
    public synchronized DialogSession openOrContinue(String conversationKey) {
        Optional<DialogSession> active = activeSession();
        if (active.isPresent()) {
            DialogSession session = active.get();
            Instant deadline = session.lastMessageAt().plus(config.sessionIdle());
            if (Instant.now().isBefore(deadline)) {
                return session.conversationKey().equals(conversationKey)
                        ? session
                        : retarget(session, conversationKey);
            }
            endSession(session.id());
            log.info("Session {} closed after idle ({})", session.id(), session.conversationKey());
        }
        return startSession(conversationKey);
    }

    /** The same conversation, now being had through another channel. */
    private DialogSession retarget(DialogSession session, String conversationKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET conversation_key = ? WHERE id = ?")) {
            ps.setString(1, conversationKey);
            ps.setLong(2, session.id());
            ps.executeUpdate();
            log.debug("Session {} continues through {} (was {})",
                    session.id(), conversationKey, session.conversationKey());
            return session(session.id()).orElse(session);
        } catch (SQLException e) {
            throw new MemoryException("Cannot move session " + session.id() + " to " + conversationKey, e);
        }
    }

    /**
     * The one conversation that is currently open. The key is ignored on purpose -- see
     * {@link #openOrContinue(String)}; the parameter is kept because callers have one at hand and
     * it makes the intent readable at the call site.
     */
    public synchronized Optional<DialogSession> activeSession(String conversationKey) {
        return activeSession();
    }

    public synchronized Optional<DialogSession> activeSession() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSession(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot read the active session", e);
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
            if (ps.executeUpdate() == 0) {

                // Already closed: two paths raced (idle close and the switch-off hook). Telling
                // the listener a second time would summarise the same conversation twice.
                return;
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot close session " + sessionId, e);
        }
        session(sessionId).ifPresent(closed -> {
            try {
                sessionClosed.accept(closed);
            } catch (RuntimeException e) {
                log.error("Session-closed listener failed for {}", sessionId, e);
            }
        });
    }

    public synchronized void saveSummary(long sessionId, String summary) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET summary = ? WHERE id = ?")) {
            ps.setString(1, summary == null ? "" : summary.strip());
            ps.setLong(2, sessionId);
            ps.executeUpdate();
            log.atInfo()
                    .addKeyValue("event", "memory.summary")
                    .addKeyValue("session_id", sessionId)
                    .addKeyValue("length", summary == null ? 0 : summary.length())
                    .log("Summary of session {} saved", sessionId);
        } catch (SQLException e) {
            throw new MemoryException("Cannot save the summary of session " + sessionId, e);
        }
    }

    /**
     * The last conversations that are over and have something to say about themselves, newest
     * first. This is the whole of episodic memory: what has been talked about and when.
     */
    public synchronized List<DialogSession> recentSummaries(int limit) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM sessions
                WHERE ended_at IS NOT NULL AND summary <> ''
                ORDER BY id DESC LIMIT ?""")) {
            ps.setInt(1, Math.max(1, limit));
            return readSessions(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read session summaries", e);
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

    /**
     * People who share a fact with this one, most shared facts first.
     *
     * <p>"Саша и Лена женаты" is already stored as one fact about two people, but nothing ever
     * walked that edge: asking about Саша told the model nothing about Лена, even though the
     * connection was right there. One hop only, and capped -- this is a nudge, not a graph
     * traversal, and the prompt is supposed to grow with the question.
     */
    public synchronized List<Entity> relatedEntities(long entityId, int limit) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT e.*, COUNT(*) AS shared FROM entities e
                JOIN fact_entities theirs ON theirs.entity_id = e.id
                JOIN fact_entities ours   ON ours.fact_id = theirs.fact_id
                JOIN facts f              ON f.id = ours.fact_id AND f.superseded_at IS NULL
                WHERE ours.entity_id = ? AND e.id <> ?
                GROUP BY e.id
                ORDER BY shared DESC, e.id
                LIMIT ?""")) {
            ps.setLong(1, entityId);
            ps.setLong(2, entityId);
            ps.setInt(3, Math.max(1, limit));
            List<Entity> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readEntity(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new MemoryException("Cannot read people related to entity " + entityId, e);
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
        return addFact(text, category, date, sourceMessageId, entityIds, FactSource.EXTRACTED, List.of());
    }

    /**
     * @param source   who decided this was worth remembering
     * @param keywords other words a question about this might use; written once, here, because
     *                 expanding the wording on every read would cost a model call per message
     */
    public synchronized Fact addFact(String text, FactCategory category, LocalDate date,
                                     Long sourceMessageId, List<Long> entityIds,
                                     FactSource source, List<String> keywords) {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO facts (text, category, fact_date, source_message_id, created_at, source, keywords) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
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
            ps.setString(6, (source == null ? FactSource.EXTRACTED : source).wireName());
            ps.setString(7, joinAliases(keywords));
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
                    .addKeyValue("source", fact.source().wireName())
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
                WHERE fe.entity_id = ? AND f.superseded_at IS NULL
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
                WHERE f.id NOT IN (SELECT fact_id FROM fact_entities) AND f.superseded_at IS NULL
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
                "SELECT * FROM facts WHERE superseded_at IS NULL ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read the facts", e);
        }
    }

    public synchronized List<Fact> factsByCategory(FactCategory category) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM facts WHERE category = ? AND superseded_at IS NULL ORDER BY id DESC")) {
            ps.setString(1, category.wireName());
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read facts of category " + category, e);
        }
    }

    /**
     * The fact is no longer current: either a newer one took its place, or the user said it was
     * wrong. Not a delete -- what was believed, and until when, stays on record, and a mistaken
     * correction can be undone by hand.
     *
     * @param replacedBy the fact that supersedes this one, or null when it was simply retracted
     * @return false when there is no such fact, or it was already superseded
     */
    public synchronized boolean supersede(long id, Long replacedBy, String reason) {
        if (replacedBy != null && replacedBy == id) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE facts SET superseded_at = ?, superseded_by = ? WHERE id = ? AND superseded_at IS NULL")) {
            ps.setString(1, Instant.now().toString());
            if (replacedBy == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, replacedBy);
            }
            ps.setLong(3, id);
            if (ps.executeUpdate() == 0) {
                return false;
            }
            log.atInfo()
                    .addKeyValue("event", "memory.superseded")
                    .addKeyValue("fact_id", id)
                    .addKeyValue("replaced_by", replacedBy == null ? "" : replacedBy.toString())
                    .addKeyValue("reason", reason == null ? "" : reason)
                    .log("Fact {} is no longer current{}", id,
                            replacedBy == null ? " (retracted)" : " (replaced by #" + replacedBy + ")");
            return true;
        } catch (SQLException e) {
            throw new MemoryException("Cannot supersede fact " + id, e);
        }
    }

    /**
     * The same thing has been heard again. Extraction runs on overlapping tails, so a repeat used
     * to be dropped as a duplicate and counted nowhere -- and a fact confirmed five times looked
     * exactly like one mentioned once in passing.
     */
    public synchronized void confirmFact(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE facts SET mention_count = mention_count + 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MemoryException("Cannot count a mention of fact " + id, e);
        }
    }

    /**
     * The model said it leaned on these facts when answering. The only signal about recall that
     * does not come from guessing which words look relevant.
     */
    public synchronized int markUsed(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE facts SET used_count = used_count + 1, last_used_at = ? WHERE id = ?")) {
            int touched = 0;
            for (Long id : ids) {
                if (id == null) {
                    continue;
                }
                ps.setString(1, now);
                ps.setLong(2, id);
                touched += ps.executeUpdate();
            }
            return touched;
        } catch (SQLException e) {
            throw new MemoryException("Cannot mark facts as used", e);
        }
    }

    /**
     * A human looked at the fact and said it was right.
     *
     * <p>The strongest thing memory can have, and the cheapest to collect: one button. Everything
     * else about a fact is either the model's judgement or a count of how often it was reused.
     */
    public synchronized boolean confirmBySource(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE facts SET source = ? WHERE id = ?")) {
            ps.setString(1, FactSource.CONFIRMED.wireName());
            ps.setLong(2, id);
            boolean done = ps.executeUpdate() > 0;
            if (done) {
                log.atInfo().addKeyValue("event", "memory.confirmed").addKeyValue("fact_id", id)
                        .log("Fact {} confirmed by the user", id);
            }
            return done;
        } catch (SQLException e) {
            throw new MemoryException("Cannot confirm fact " + id, e);
        }
    }

    /**
     * Facts the model picked out of a conversation and nobody has looked at yet, newest first.
     *
     * <p>A review queue rather than a notification: a message every few minutes saying "я запомнил
     * ещё три вещи" is noise that gets muted, and a muted channel collects no signal at all. This
     * waits in the menu and drains when the user feels like draining it.
     */
    public synchronized List<Fact> unreviewedFacts(int limit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM facts WHERE superseded_at IS NULL AND source = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, FactSource.EXTRACTED.wireName());
            ps.setInt(2, Math.max(1, limit));
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read unreviewed facts", e);
        }
    }

    public synchronized int countUnreviewed() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM facts WHERE superseded_at IS NULL AND source = ?")) {
            ps.setString(1, FactSource.EXTRACTED.wireName());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new MemoryException("Cannot count unreviewed facts", e);
        }
    }

    /** What is no longer current, newest first -- for diagnostics and for undoing a mistake. */
    public synchronized List<Fact> supersededFacts(int limit) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM facts WHERE superseded_at IS NOT NULL ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            return readFacts(ps);
        } catch (SQLException e) {
            throw new MemoryException("Cannot read superseded facts", e);
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

    /** Only what is still believed: superseded facts stay on disk but are not "what I know". */
    public synchronized int countFacts() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM facts WHERE superseded_at IS NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MemoryException("Cannot count facts", e);
        }
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
                rs.getLong("consolidated_up_to"),
                rs.getString("summary"));
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
        long replacedBy = rs.getLong("superseded_by");
        boolean replacedByNull = rs.wasNull();
        String supersededAt = rs.getString("superseded_at");
        String lastUsed = rs.getString("last_used_at");
        return new Fact(
                id,
                rs.getString("text"),
                FactCategory.fromWire(rs.getString("category")),
                date == null ? null : LocalDate.parse(date),
                sourceNull ? null : source,
                entityIdsOf(id),
                Instant.parse(rs.getString("created_at")),
                replacedByNull ? null : replacedBy,
                supersededAt == null ? null : Instant.parse(supersededAt),
                rs.getInt("mention_count"),
                rs.getInt("used_count"),
                lastUsed == null ? null : Instant.parse(lastUsed),
                FactSource.fromWire(rs.getString("source")),
                splitAliases(rs.getString("keywords")));
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
