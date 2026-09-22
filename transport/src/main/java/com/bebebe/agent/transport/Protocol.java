package com.bebebe.agent.transport;

public final class Protocol {

    private Protocol() {
    }

    public static final int VERSION = 1;

    public static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    public static final int CLOSE_AUTH_REJECTED = 4401;

    public static final int CLOSE_AUTH_TIMEOUT = 4408;

    public static final int CLOSE_REPLACED = 4409;

    public static final int CLOSE_HEARTBEAT_TIMEOUT = 4499;

    public static final int CLOSE_SERVER_SHUTDOWN = 4001;

    public static final int CLOSE_KICKED = 4002;

    public static String describeClose(int code) {
        return switch (code) {
            case CLOSE_AUTH_REJECTED -> "authentication rejected";
            case CLOSE_AUTH_TIMEOUT -> "AUTH not received";
            case CLOSE_REPLACED -> "replaced by a new connection of the same client";
            case CLOSE_HEARTBEAT_TIMEOUT -> "no answer to PING";
            case CLOSE_SERVER_SHUTDOWN -> "server is shutting down";
            case CLOSE_KICKED -> "closed by the server";
            case 1000 -> "normal closure";
            case 1006 -> "connection dropped";
            default -> "code " + code;
        };
    }
}
