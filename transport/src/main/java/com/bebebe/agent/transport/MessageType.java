package com.bebebe.agent.transport;

import java.util.Locale;
import java.util.Optional;

public enum MessageType {

    AUTH,

    AUTH_RESULT,

    PING,
    PONG,

    STATUS_PUSH,

    RUN_SCRIPT_REQUEST,

    RUN_SCRIPT_RESULT,

    CLIPBOARD_REQUEST,
    CLIPBOARD_RESULT,

    VOICE_AUDIO_PUSH,

    ERROR;

    public String wireName() {
        return name();
    }

    public static Optional<MessageType> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (MessageType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
