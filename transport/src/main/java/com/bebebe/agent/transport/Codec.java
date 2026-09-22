package com.bebebe.agent.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

public final class Codec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Codec() {
    }

    public static String encode(Envelope envelope) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", envelope.type().wireName());
        node.put("id", envelope.id());
        if (envelope.replyTo() != null) {
            node.put("reply_to", envelope.replyTo());
        }
        if (envelope.traceId() != null) {
            node.put("trace_id", envelope.traceId());
        }
        node.put("sent_at", envelope.sentAt().toEpochMilli());
        node.set("payload", envelope.payload());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("Cannot serialise " + envelope, e);
        }
    }

    public static Envelope decode(String text) {
        if (text == null || text.isBlank()) {
            throw new ProtocolException("Empty message");
        }
        if (text.length() > Protocol.MAX_MESSAGE_BYTES) {
            throw new ProtocolException("Message longer than " + Protocol.MAX_MESSAGE_BYTES + " bytes");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("Not JSON: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new ProtocolException("Message must be a JSON object");
        }
        String rawType = node.path("type").asText(null);
        MessageType type = MessageType.fromWire(rawType)
                .orElseThrow(() -> new ProtocolException("Unknown message type: " + rawType));
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ProtocolException("Message " + type + " has no id");
        }
        JsonNode payload = node.path("payload");
        if (!payload.isMissingNode() && !payload.isNull() && !payload.isObject()) {
            throw new ProtocolException("payload of " + type + " must be an object");
        }
        Instant sentAt = node.path("sent_at").isNumber()
                ? Instant.ofEpochMilli(node.path("sent_at").asLong())
                : Instant.now();
        return new Envelope(type, id,
                node.path("reply_to").asText(null),
                node.path("trace_id").asText(null),
                sentAt,
                payload.isMissingNode() ? MAPPER.createObjectNode() : payload);
    }

    static JsonNode toNode(Object payload) {
        if (payload == null) {
            return MAPPER.createObjectNode();
        }
        if (payload instanceof JsonNode node) {
            return node;
        }
        JsonNode node = MAPPER.valueToTree(payload);
        if (!node.isObject()) {
            throw new ProtocolException("payload must serialise to an object, not to " + node.getNodeType());
        }
        return node;
    }

    static <T> T fromNode(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node == null || node.isNull() ? MAPPER.createObjectNode() : node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ProtocolException("payload cannot be parsed as " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
