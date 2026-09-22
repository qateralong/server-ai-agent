package com.bebebe.agent.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

public final class OllamaStats {

    private static final Logger log = LoggerFactory.getLogger(OllamaStats.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Snapshot(long sessionCalls, long sessionTokens, long sessionFailures,
                           long totalCalls, long totalTokens, long totalFailures,
                           Optional<Instant> lastSuccess, Optional<Instant> lastFailure,
                           String lastError, Instant sessionStarted) {

        public boolean available() {
            if (lastFailure.isEmpty()) {
                return lastSuccess.isPresent();
            }
            return lastSuccess.map(s -> s.isAfter(lastFailure.get())).orElse(false);
        }
    }

    private final Object lock = new Object();
    private final Instant sessionStarted = Instant.now();
    private long sessionCalls;
    private long sessionTokens;
    private long sessionFailures;
    private long totalCalls;
    private long totalTokens;
    private long totalFailures;
    private Instant lastSuccess;
    private Instant lastFailure;
    private String lastError = "";
    private Path file;

    public void persistTo(Path file) {
        synchronized (lock) {
            this.file = file;
            if (Files.exists(file)) {
                try {
                    JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                    totalCalls = node.path("calls").asLong();
                    totalTokens = node.path("tokens").asLong();
                    totalFailures = node.path("failures").asLong();
                } catch (IOException | RuntimeException e) {
                    log.warn("Ollama stats not read from {}: {}", file, e.getMessage());
                }
            }
        }
    }

    void success(ChatResponse response) {
        success(response.promptEvalCount(), response.evalCount());
    }

    public void success(Integer inputTokens, Integer outputTokens) {
        long tokens = (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
        synchronized (lock) {
            sessionCalls++;
            totalCalls++;
            sessionTokens += tokens;
            totalTokens += tokens;
            lastSuccess = Instant.now();
        }
        flush();
    }

    public void failure(String message) {
        synchronized (lock) {
            sessionCalls++;
            totalCalls++;
            sessionFailures++;
            totalFailures++;
            lastFailure = Instant.now();
            lastError = message == null ? "" : message;
        }
        flush();
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(sessionCalls, sessionTokens, sessionFailures, totalCalls, totalTokens, totalFailures,
                    Optional.ofNullable(lastSuccess), Optional.ofNullable(lastFailure), lastError, sessionStarted);
        }
    }

    private void flush() {
        Path target;
        ObjectNode node = JSON.createObjectNode();
        synchronized (lock) {
            target = file;
            if (target == null) {
                return;
            }
            node.put("calls", totalCalls).put("tokens", totalTokens).put("failures", totalFailures);
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, node.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.debug("Ollama stats not written: {}", e.getMessage());
        }
    }
}
