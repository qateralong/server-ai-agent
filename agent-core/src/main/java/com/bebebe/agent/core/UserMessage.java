package com.bebebe.agent.core;

import com.bebebe.agent.logging.TraceContext;

import java.time.Instant;
import java.util.Optional;

public record UserMessage(
        MessageSource source,
        String text,
        Instant receivedAt,
        String replyTo,
        String traceId
) {

    public UserMessage {
        if (source == null) {
            throw new IllegalArgumentException("UserMessage: source not set");
        }
        text = text == null ? "" : text.strip();
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;

        traceId = traceId == null || traceId.isBlank() ? TraceContext.currentOrNew() : traceId;
    }

    public UserMessage(MessageSource source, String text, Instant receivedAt, String replyTo) {
        this(source, text, receivedAt, replyTo, null);
    }

    public static UserMessage telegram(String text, long chatId) {
        return new UserMessage(MessageSource.TELEGRAM, text, Instant.now(), Long.toString(chatId));
    }

    public static UserMessage voice(String text) {
        return new UserMessage(MessageSource.VOICE, text, Instant.now(), null);
    }

    public static UserMessage voice(String text, String traceId) {
        return new UserMessage(MessageSource.VOICE, text, Instant.now(), null, traceId);
    }

    public static UserMessage system(String text, String conversationKey, String traceId) {
        String replyTo = conversationKey != null && conversationKey.startsWith("TELEGRAM:")
                ? conversationKey.substring("TELEGRAM:".length())
                : null;
        return new UserMessage(MessageSource.SYSTEM, text, Instant.now(), replyTo, traceId);
    }

    public static UserMessage clipboard(String text) {
        return new UserMessage(MessageSource.CLIPBOARD, text, Instant.now(), null);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    public Optional<String> replyTarget() {
        return replyTo == null || replyTo.isBlank() ? Optional.empty() : Optional.of(replyTo);
    }

    @Override
    public String toString() {
        String preview = text.length() <= 80 ? text : text.substring(0, 80) + "…";
        return "[" + source.title() + "] " + preview;
    }
}
