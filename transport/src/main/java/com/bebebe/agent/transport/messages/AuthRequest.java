package com.bebebe.agent.transport.messages;

public record AuthRequest(String clientId, String clientName, String token, int protocolVersion) {
}
