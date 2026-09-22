package com.bebebe.agent.transport.messages;

import java.util.Map;

public record RunScriptRequest(String code, Map<String, Object> arguments, int timeoutSeconds) {

    public RunScriptRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
