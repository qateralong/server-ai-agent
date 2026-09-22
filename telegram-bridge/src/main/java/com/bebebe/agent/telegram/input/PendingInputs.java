package com.bebebe.agent.telegram.input;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingInputs {

    private static final Logger log = LoggerFactory.getLogger(PendingInputs.class);

    private final Map<Long, PendingInput> byChat = new ConcurrentHashMap<>();

    public void await(long chatId, PendingInput input) {
        PendingInput previous = byChat.put(chatId, input);
        if (previous != null) {
            log.debug("Chat {}: pending input '{}' replaced with '{}'", chatId, previous.fieldKey(), input.fieldKey());
        } else {
            log.debug("Chat {}: waiting for input for '{}'", chatId, input.fieldKey());
        }
    }

    public Optional<PendingInput> peek(long chatId) {
        PendingInput input = byChat.get(chatId);
        if (input == null) {
            return Optional.empty();
        }
        if (input.isExpired(Instant.now())) {
            byChat.remove(chatId, input);
            log.debug("Chat {}: pending input '{}' expired", chatId, input.fieldKey());
            return Optional.empty();
        }
        return Optional.of(input);
    }

    public Optional<PendingInput> consume(long chatId) {
        Optional<PendingInput> input = peek(chatId);
        input.ifPresent(value -> byChat.remove(chatId, value));
        return input;
    }

    public boolean cancel(long chatId) {
        PendingInput removed = byChat.remove(chatId);
        if (removed != null) {
            log.debug("Chat {}: pending input '{}' cancelled", chatId, removed.fieldKey());
        }
        return removed != null;
    }

    public int size() {
        return byChat.size();
    }
}
