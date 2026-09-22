package com.bebebe.agent.scheduler;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JobStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    private final Connection connection;
    private final SchedulerConfig config;

    public JobStore(SchedulerConfig config) {
        this.config = config;
        try {
            if (config.dbPath().getParent() != null) {
                Files.createDirectories(config.dbPath().getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + config.dbPath());
            migrate();
            log.info("Scheduler: {} (pending {})", config.dbPath(), countPending());
        } catch (IOException | SQLException e) {
            throw new SchedulerException("Cannot open scheduler database: " + config.dbPath(), e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS jobs (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        fire_at          TEXT NOT NULL,
                        prompt           TEXT NOT NULL,
                        summary          TEXT NOT NULL DEFAULT '',
                        conversation_key TEXT NOT NULL DEFAULT '',
                        repeat           TEXT NOT NULL DEFAULT '',
                        status           TEXT NOT NULL DEFAULT 'PENDING',
                        created_at       TEXT NOT NULL,
                        fired_at         TEXT,
                        trace_id         TEXT
                    )""");

            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_jobs_due ON jobs(status, fire_at)");
        }
    }

    public SchedulerConfig config() {
        return config;
    }

    public synchronized Job add(Instant fireAt, String prompt, String summary,
                                String conversationKey, Repeat repeat, String traceId) {
        Instant now = Instant.now();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO jobs (fire_at, prompt, summary, conversation_key, repeat, status, created_at, trace_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, fireAt.toString());
            ps.setString(2, prompt);
            ps.setString(3, summary == null ? "" : summary);
            ps.setString(4, conversationKey == null ? "" : conversationKey);
            ps.setString(5, repeat.toWire());
            ps.setString(6, now.toString());
            ps.setString(7, traceId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                Job job = byId(keys.getLong(1)).orElseThrow();
                log.atInfo()
                        .addKeyValue("event", "job.created")
                        .addKeyValue("job_id", job.id())
                        .addKeyValue("fire_at", fireAt.toString())
                        .addKeyValue("repeat", repeat.toWire())
                        .log("Job {}: «{}» at {}", job.id(), job.summary(), fireAt);
                return job;
            }
        } catch (SQLException e) {
            throw new SchedulerException("Cannot write job", e);
        }
    }

    public synchronized void markFired(long id, JobStatus outcome) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE jobs SET status = ?, fired_at = ? WHERE id = ? AND status = 'PENDING'")) {
            ps.setString(1, outcome.name());
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SchedulerException("Cannot mark job " + id, e);
        }
    }

    public synchronized boolean cancel(long id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE jobs SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'")) {
            ps.setLong(1, id);
            boolean done = ps.executeUpdate() > 0;
            if (done) {
                log.info("Job {} cancelled", id);
            }
            return done;
        } catch (SQLException e) {
            throw new SchedulerException("Cannot cancel job " + id, e);
        }
    }

    public synchronized boolean reschedule(long id, Instant fireAt) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE jobs SET fire_at = ? WHERE id = ? AND status = 'PENDING'")) {
            ps.setString(1, fireAt.toString());
            ps.setLong(2, id);
            boolean done = ps.executeUpdate() > 0;
            if (done) {
                log.info("Job {} rescheduled to {}", id, fireAt);
            }
            return done;
        } catch (SQLException e) {
            throw new SchedulerException("Cannot reschedule job " + id, e);
        }
    }

    public synchronized int deleteHistory() {
        try (Statement s = connection.createStatement()) {
            return s.executeUpdate("DELETE FROM jobs WHERE status != 'PENDING'");
        } catch (SQLException e) {
            throw new SchedulerException("Cannot clear history", e);
        }
    }

    public synchronized Optional<Job> byId(long id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM jobs WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new SchedulerException("Cannot read job " + id, e);
        }
    }

    public synchronized List<Job> due(Instant now) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM jobs WHERE status = 'PENDING' AND fire_at <= ? ORDER BY fire_at")) {
            ps.setString(1, now.toString());
            return readAll(ps);
        } catch (SQLException e) {
            throw new SchedulerException("Cannot read due jobs", e);
        }
    }

    public synchronized List<Job> pending() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM jobs WHERE status = 'PENDING' ORDER BY fire_at")) {
            return readAll(ps);
        } catch (SQLException e) {
            throw new SchedulerException("Cannot read pending jobs", e);
        }
    }

    public synchronized List<Job> history() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM jobs WHERE status != 'PENDING' ORDER BY COALESCE(fired_at, created_at) DESC, id DESC")) {
            return readAll(ps);
        } catch (SQLException e) {
            throw new SchedulerException("Cannot read history", e);
        }
    }

    public synchronized int countPending() {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM jobs WHERE status = 'PENDING'")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new SchedulerException("Cannot count jobs", e);
        }
    }

    private List<Job> readAll(PreparedStatement ps) throws SQLException {
        List<Job> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(read(rs));
            }
        }
        return list;
    }

    private static Job read(ResultSet rs) throws SQLException {
        String fired = rs.getString("fired_at");
        return new Job(
                rs.getLong("id"),
                Instant.parse(rs.getString("fire_at")),
                rs.getString("prompt"),
                rs.getString("summary"),
                rs.getString("conversation_key"),
                Repeat.fromWire(rs.getString("repeat")),
                JobStatus.fromWire(rs.getString("status")),
                Instant.parse(rs.getString("created_at")),
                fired == null ? null : Instant.parse(fired),
                rs.getString("trace_id"));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Cannot close scheduler database", e);
        }
    }

    public static class SchedulerException extends RuntimeException {
        public SchedulerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
