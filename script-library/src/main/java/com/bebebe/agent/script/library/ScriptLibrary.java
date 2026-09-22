package com.bebebe.agent.script.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ScriptLibrary implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScriptLibrary.class);

    public static final boolean DEFAULT_REQUIRES_CONFIRMATION = true;

    private final LibraryConfig config;
    private final Connection connection;

    public ScriptLibrary(LibraryConfig config) {
        this.config = config;
        try {
            Files.createDirectories(config.scriptsDir());
            if (config.dbPath().getParent() != null) {
                Files.createDirectories(config.dbPath().getParent());
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + config.dbPath());
            migrate();
            log.info("Script catalog: {} ({} entries)", config.dbPath(), count());
        } catch (IOException | SQLException e) {
            throw new LibraryException("Cannot open script catalog: " + config.dbPath(), e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS scripts (
                        id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                        name                  TEXT    NOT NULL,
                        description           TEXT    NOT NULL DEFAULT '',
                        tags                  TEXT    NOT NULL DEFAULT '',
                        path                  TEXT    NOT NULL,
                        version               INTEGER NOT NULL DEFAULT 1,
                        root_id               INTEGER,
                        success_count         INTEGER NOT NULL DEFAULT 0,
                        failure_count         INTEGER NOT NULL DEFAULT 0,
                        requires_confirmation INTEGER NOT NULL DEFAULT 1,
                        created_at            TEXT    NOT NULL,
                        last_used_at          TEXT
                    )""");

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_scripts_root ON scripts(root_id)");
        }
    }

    public synchronized ScriptEntry add(String name, String description, List<String> tags, String code) {
        return insert(name, description, tags, code, 1, null);
    }

    public synchronized ScriptEntry addVersion(ScriptEntry previous, String code) {
        long root = previous.rootId();
        int nextVersion = maxVersion(root) + 1;
        ScriptEntry created = insert(
                previous.name(), previous.description(), previous.tags(), code, nextVersion, root);
        log.info("Saved version {} of script «{}» (id {})",
                nextVersion, previous.name(), created.id());
        return created;
    }

    private ScriptEntry insert(String name,
                               String description,
                               List<String> tags,
                               String code,
                               int version,
                               Long rootId) {
        String safeName = sanitizeName(name);
        Instant now = Instant.now();
        Path file = config.scriptsDir().resolve(
                "%s-v%d-%d.py".formatted(safeName, version, now.toEpochMilli()));

        for (int n = 2; Files.exists(file); n++) {
            file = config.scriptsDir().resolve(
                    "%s-v%d-%d-%d.py".formatted(safeName, version, now.toEpochMilli(), n));
        }

        try {
            Files.writeString(file, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LibraryException("Cannot save script file: " + file, e);
        }

        String sql = """
                INSERT INTO scripts (name, description, tags, path, version, root_id,
                                     requires_confirmation, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setString(3, String.join(",", tags == null ? List.of() : tags));
            statement.setString(4, file.toString());
            statement.setInt(5, version);
            if (rootId == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setLong(6, rootId);
            }
            statement.setInt(7, DEFAULT_REQUIRES_CONFIRMATION ? 1 : 0);
            statement.setString(8, now.toString());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);

                if (rootId == null) {
                    setRootToSelf(id);
                }
                return byId(id).orElseThrow(() ->
                        new LibraryException("Entry " + id + " disappeared right after insert"));
            }
        } catch (SQLException e) {
            throw new LibraryException("Cannot add script to catalog", e);
        }
    }

    private void setRootToSelf(long id) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("UPDATE scripts SET root_id = id WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private int maxVersion(long rootId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(version), 0) FROM scripts WHERE root_id = ?")) {
            statement.setLong(1, rootId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new LibraryException("Cannot determine script version", e);
        }
    }

    public synchronized Optional<ScriptEntry> byId(long id) {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT * FROM scripts WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LibraryException("Cannot read script " + id, e);
        }
    }

    public Optional<String> codeOf(ScriptEntry entry) {
        try {
            return Files.isReadable(entry.path())
                    ? Optional.of(Files.readString(entry.path(), StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            log.warn("Cannot read script file {}: {}", entry.path(), e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized List<ScriptEntry> latestVersions() {
        String sql = """
                SELECT s.* FROM scripts s
                JOIN (SELECT root_id, MAX(version) AS v FROM scripts GROUP BY root_id) m
                  ON s.root_id = m.root_id AND s.version = m.v
                ORDER BY s.name COLLATE NOCASE, s.id""";
        return query(sql);
    }

    public synchronized List<ScriptEntry> allVersionsOf(long rootId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM scripts WHERE root_id = ? ORDER BY version")) {
            statement.setLong(1, rootId);
            return read(statement);
        } catch (SQLException e) {
            throw new LibraryException("Cannot read versions of script " + rootId, e);
        }
    }

    public List<ScriptEntry> search(String query) {
        return ScriptSearch.find(query, latestVersions(), config.searchLimit());
    }

    public synchronized int count() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM scripts")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new LibraryException("Cannot count scripts", e);
        }
    }

    public synchronized void recordRun(long id, boolean success) {
        String column = success ? "success_count" : "failure_count";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE scripts SET " + column + " = " + column + " + 1, last_used_at = ? WHERE id = ?")) {
            statement.setString(1, Instant.now().toString());
            statement.setLong(2, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("Cannot update stats of script {}: {}", id, e.getMessage());
        }
    }

    public synchronized void setRequiresConfirmation(long id, boolean required) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE scripts SET requires_confirmation = ? WHERE id = ?")) {
            statement.setInt(1, required ? 1 : 0);
            statement.setLong(2, id);
            statement.executeUpdate();
            log.info("Script {}: confirmation {}", id, required ? "enabled" : "disabled");
        } catch (SQLException e) {
            throw new LibraryException("Cannot change confirmation flag of script " + id, e);
        }
    }

    public synchronized void updateDescription(long id, String name, String description, List<String> tags) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE scripts SET name = ?, description = ?, tags = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setString(3, String.join(",", tags));
            statement.setLong(4, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new LibraryException("Cannot update description of script " + id, e);
        }
    }

    private List<ScriptEntry> query(String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return read(statement);
        } catch (SQLException e) {
            throw new LibraryException("Cannot read script catalog", e);
        }
    }

    private List<ScriptEntry> read(PreparedStatement statement) throws SQLException {
        List<ScriptEntry> entries = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                entries.add(read(rs));
            }
        }
        return entries;
    }

    private static ScriptEntry read(ResultSet rs) throws SQLException {
        String rawTags = rs.getString("tags");
        List<String> tags = rawTags == null || rawTags.isBlank()
                ? List.of()
                : Arrays.stream(rawTags.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();

        String lastUsed = rs.getString("last_used_at");
        long id = rs.getLong("id");
        long root = rs.getLong("root_id");

        return new ScriptEntry(
                id,
                rs.getString("name"),
                rs.getString("description"),
                tags,
                Path.of(rs.getString("path")),
                rs.getInt("version"),
                root == 0 ? id : root,
                rs.getInt("success_count"),
                rs.getInt("failure_count"),
                rs.getInt("requires_confirmation") != 0,
                Instant.parse(rs.getString("created_at")),
                lastUsed == null ? null : Instant.parse(lastUsed));
    }

    static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "script";
        }
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.isEmpty()) {
            return "script";
        }
        return cleaned.length() <= 48 ? cleaned : cleaned.substring(0, 48);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Cannot close catalog connection", e);
        }
    }

    public static class LibraryException extends RuntimeException {

        public LibraryException(String message) {
            super(message);
        }

        public LibraryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
