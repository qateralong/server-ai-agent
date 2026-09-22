package com.bebebe.agent.transport.messages;

public record RunScriptResult(int exitCode, String stdout, String stderr, long durationMs, boolean timeout) {

    public boolean isSuccess() {
        return exitCode == 0 && !timeout;
    }
}
