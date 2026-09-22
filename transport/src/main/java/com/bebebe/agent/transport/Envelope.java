package com.bebebe.agent.transport;

import com.bebebe.agent.logging.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

public record Envelope(
        MessageType type,
        String id,
        String replyTo,
        String traceId,
        Instant sentAt,
        JsonNode payload
) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public Envelope {
        if (type == null) {
            throw new ProtocolException("Message has no type");
        }
        if (id == null || id.isBlank()) {
            throw new ProtocolException("Message has no id");
        }
        if (sentAt == null) {
            sentAt = Instant.now();
        }
        if (payload == null) {
            payload = NullNode.getInstance();
        }
    }

    public static Envelope of(MessageType type, Object payload) {
        return new Envelope(type, newId(), null, TraceContext.current().orElse(null),
                Instant.now(), Codec.toNode(payload));
    }

    public static Envelope of(MessageType type) {
        return of(type, java.util.Map.of());
    }

    public Envelope reply(MessageType type, Object payload) {
        return new Envelope(type, newId(), id, traceId, Instant.now(), Codec.toNode(payload));
    }

    public Envelope reply(MessageType type) {
        return reply(type, java.util.Map.of());
    }

    public <T> T payloadAs(Class<T> type) {
        return Codec.fromNode(payload, type);
    }

    public boolean isReply() {
        return replyTo != null && !replyTo.isBlank();
    }

    public static String newId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public String toString() {
        return type + "#" + id + (isReply() ? "→" + replyTo : "");
    }
}
