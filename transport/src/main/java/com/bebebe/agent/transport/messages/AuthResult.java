package com.bebebe.agent.transport.messages;

public record AuthResult(boolean accepted, String reason, String serverName, int heartbeatSeconds) {

    public static AuthResult ok(String serverName, int heartbeatSeconds) {
        return new AuthResult(true, "", serverName, heartbeatSeconds);
    }

    public static AuthResult rejected(String reason) {
        return new AuthResult(false, reason, "", 0);
    }
}
