package com.bebebe.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfirmationStore {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationStore.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, PendingExecution> pending = new ConcurrentHashMap<>();

    public String put(UserMessage message,
                      com.bebebe.agent.script.library.ScriptEntry script,
                      String code,
                      RequestBudget budget) {
        purgeExpired();
        String token = newToken();
        pending.put(token, new PendingExecution(
                token, message, script, code, budget, Instant.now().plus(PendingExecution.TTL)));
        log.info("Waiting for confirmation to launch '{}', token {}", script.displayName(), token);
        return token;
    }

    public Optional<PendingExecution> take(String token) {
        PendingExecution execution = pending.remove(token);
        if (execution == null) {
            return Optional.empty();
        }
        if (execution.isExpired(Instant.now())) {
            log.info("Confirmation {} expired", token);
            return Optional.empty();
        }
        return Optional.of(execution);
    }

    public Optional<PendingExecution> peek(String token) {
        return Optional.ofNullable(pending.get(token))
                .filter(execution -> !execution.isExpired(Instant.now()));
    }

    public boolean cancel(String token) {
        return pending.remove(token) != null;
    }

    public int size() {
        purgeExpired();
        return pending.size();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        pending.values().removeIf(execution -> execution.isExpired(now));
    }

    private static String newToken() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
