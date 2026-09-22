package com.bebebe.agent.clipboard;

import com.bebebe.agent.transport.actions.ClipboardTool;

import java.util.Optional;

public final class LocalClipboardTool implements ClipboardTool {

    private final ClipboardBridge bridge;

    public LocalClipboardTool(ClipboardBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public boolean isReady() {
        return bridge.isReady();
    }

    @Override
    public Optional<String> read() {
        return bridge.read();
    }
}
