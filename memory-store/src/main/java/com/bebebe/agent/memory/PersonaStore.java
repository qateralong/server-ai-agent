package com.bebebe.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PersonaStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PersonaStore.class);

    public static final String DEFAULT_NAME = "Default";
    public static final String DEFAULT_PROMPT =
            "You are a personal assistant on a Linux desktop. Answer in Russian, briefly and to the point, "
                    + "without preambles. If you do not know something, say so directly.";

    private final Connection connection;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PersonaStore(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("PRAGMA journal_mode=WAL");
                s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS personas (
                            id         INTEGER PRIMARY KEY AUTOINCREMENT,
                            name       TEXT NOT NULL,
                            prompt     TEXT NOT NULL DEFAULT '',
                            created_at TEXT NOT NULL,
                            active     INTEGER NOT NULL DEFAULT 0
                        )""");
            }
            if (all().isEmpty()) {
                create(DEFAULT_NAME, DEFAULT_PROMPT);
                log.info("No personas existed -- created «{}»", DEFAULT_NAME);
            } else if (active().isEmpty()) {
                activate(all().getFirst().id());
            }
            log.info("Personas: {} (active: «{}»)", dbPath, active().map(Persona::name).orElse("?"));
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("Cannot open personas database " + dbPath + ": " + e.getMessage(), e);
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public synchronized List<Persona> all() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM personas ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            List<Persona> out = new ArrayList<>();
            while (rs.next()) {
                out.add(read(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<Persona> byId(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM personas WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<Persona> active() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM personas WHERE active = 1 LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
    }

    public String promptBlock() {
        return active().filter(p -> !p.prompt().isBlank())
                .map(p -> "\n\nPersona «" + p.name() + "» -- follow this instruction in all replies:\n" + p.prompt().strip())
                .orElse("");
    }

    public synchronized Persona create(String name, String prompt) {
        String clean = requireName(name);
        boolean first = all().isEmpty();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO personas (name, prompt, created_at, active) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, clean);
            ps.setString(2, prompt == null ? "" : prompt.strip());
            ps.setString(3, Instant.now().toString());
            ps.setInt(4, first ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                Persona created = byId(keys.getLong(1)).orElseThrow();
                log.atInfo().addKeyValue("event", "persona.create").addKeyValue("name", clean)
                        .log("Persona «{}» created", clean);
                notifyListeners();
                return created;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
    }

    public synchronized Persona update(long id, String name, String prompt) {
        String clean = requireName(name);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE personas SET name = ?, prompt = ? WHERE id = ?")) {
            ps.setString(1, clean);
            ps.setString(2, prompt == null ? "" : prompt.strip());
            ps.setLong(3, id);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Persona #" + id + " not found");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
        notifyListeners();
        return byId(id).orElseThrow();
    }

    public Persona updatePrompt(long id, String prompt) {
        Persona current = byId(id).orElseThrow(() -> new IllegalArgumentException("Persona #" + id + " not found"));
        return update(id, current.name(), prompt);
    }

    public synchronized boolean activate(long id) {
        try {
            if (byId(id).isEmpty()) {
                return false;
            }
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement();
                 PreparedStatement ps = connection.prepareStatement("UPDATE personas SET active = 1 WHERE id = ?")) {
                s.executeUpdate("UPDATE personas SET active = 0");
                ps.setLong(1, id);
                ps.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
        log.atInfo().addKeyValue("event", "persona.activate").addKeyValue("id", id)
                .log("Active persona: «{}»", byId(id).map(Persona::name).orElse("?"));
        notifyListeners();
        return true;
    }

    public synchronized boolean delete(long id) {
        List<Persona> all = all();
        Optional<Persona> target = all.stream().filter(p -> p.id() == id).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        if (all.size() == 1) {
            throw new IllegalStateException("Cannot delete the last persona");
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM personas WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Personas: " + e.getMessage(), e);
        }
        if (target.get().active()) {
            activate(all.stream().filter(p -> p.id() != id).findFirst().orElseThrow().id());
        }
        log.atInfo().addKeyValue("event", "persona.delete").addKeyValue("name", target.get().name())
                .log("Persona «{}» deleted", target.get().name());
        notifyListeners();
        return true;
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 60) {
            throw new IllegalArgumentException("Persona name: 1 to 60 characters");
        }
        return clean;
    }

    private void notifyListeners() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                log.warn("Persona listener failed: {}", e.toString());
            }
        }
    }

    private static Persona read(ResultSet rs) throws SQLException {
        return new Persona(rs.getLong("id"), rs.getString("name"), rs.getString("prompt"),
                Instant.parse(rs.getString("created_at")), rs.getInt("active") == 1);
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Personas database closed with an error: {}", e.getMessage());
        }
    }
}
