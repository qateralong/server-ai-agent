package com.bebebe.agent.notes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class NotesIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotesIndex.class);

    record Row(long id, String path, String title, NoteKind kind, List<String> tags,
               Instant updatedAt, String preview) { }

    private final Connection connection;

    NotesIndex(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("PRAGMA journal_mode=WAL");
                s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS documents (
                            id         INTEGER PRIMARY KEY AUTOINCREMENT,
                            path       TEXT NOT NULL UNIQUE,
                            title      TEXT NOT NULL,
                            kind       TEXT NOT NULL,
                            tags       TEXT NOT NULL DEFAULT '',
                            updated_at TEXT NOT NULL,
                            preview    TEXT NOT NULL DEFAULT ''
                        )""");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_documents_title ON documents(title)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open notes index " + dbPath + ": " + e.getMessage(), e);
        }
    }

    synchronized long upsert(String relPath, String title, NoteKind kind, List<String> tags,
                             Instant updatedAt, String preview) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO documents (path, title, kind, tags, updated_at, preview)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET title = excluded.title, kind = excluded.kind,
                    tags = excluded.tags, updated_at = excluded.updated_at, preview = excluded.preview""")) {
            ps.setString(1, relPath);
            ps.setString(2, title);
            ps.setString(3, kind.wire());
            ps.setString(4, String.join("|", tags));
            ps.setString(5, updatedAt.toString());
            ps.setString(6, preview);
            ps.executeUpdate();
            return byPath(relPath).map(Row::id).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Notes index: " + e.getMessage(), e);
        }
    }

    synchronized void remove(String relPath) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM documents WHERE path = ?")) {
            ps.setString(1, relPath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Notes index: " + e.getMessage(), e);
        }
    }

    synchronized int retainOnly(java.util.Set<String> existing) {
        int removed = 0;
        for (Row row : all()) {
            if (!existing.contains(row.path())) {
                remove(row.path());
                removed++;
            }
        }
        return removed;
    }

    synchronized Optional<Row> byId(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM documents WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Notes index: " + e.getMessage(), e);
        }
    }

    synchronized Optional<Row> byPath(String relPath) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM documents WHERE path = ?")) {
            ps.setString(1, relPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Notes index: " + e.getMessage(), e);
        }
    }

    synchronized List<Row> all() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM documents ORDER BY updated_at DESC, id DESC");
             ResultSet rs = ps.executeQuery()) {
            List<Row> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Notes index: " + e.getMessage(), e);
        }
    }

    private static Row read(ResultSet rs) throws SQLException {
        String tags = rs.getString("tags");
        return new Row(rs.getLong("id"), rs.getString("path"), rs.getString("title"),
                NoteKind.fromWire(rs.getString("kind")),
                tags.isEmpty() ? List.of() : List.of(tags.split("\\|")),
                Instant.parse(rs.getString("updated_at")), rs.getString("preview"));
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Notes index closed with an error: {}", e.getMessage());
        }
    }
}
