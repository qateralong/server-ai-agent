package com.bebebe.agent.core;

import com.bebebe.agent.logging.TraceContext;

import java.time.Instant;
import java.util.Optional;

public record UserMessage(
        MessageSource source,
        String text,
        Instant receivedAt,
        String replyTo,
        String traceId,

        /**
         * Pictures that came with this message. Context for one turn only: they are handed to
         * the model with this question and are not written to the session log or into a script.
         */
        java.util.List<com.bebebe.agent.llm.LlmImage> images
) {

    public UserMessage {
        if (source == null) {
            throw new IllegalArgumentException("UserMessage: source not set");
        }
        text = text == null ? "" : text.strip();
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;

        traceId = traceId == null || traceId.isBlank() ? TraceContext.currentOrNew() : traceId;
        images = images == null ? java.util.List.of() : java.util.List.copyOf(images);
    }

    public UserMessage(MessageSource source, String text, Instant receivedAt, String replyTo, String traceId) {
        this(source, text, receivedAt, replyTo, traceId, java.util.List.of());
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    /** A photo with a caption, or without one -- then the caption stands in for the question. */
    public static UserMessage image(String caption, long chatId, String traceId,
                                    java.util.List<com.bebebe.agent.llm.LlmImage> images) {
        String text = caption == null || caption.isBlank()
                ? "Посмотри на изображение и скажи, что на нём."
                : caption;
        return new UserMessage(MessageSource.IMAGE, text, Instant.now(),
                Long.toString(chatId), traceId, images);
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
