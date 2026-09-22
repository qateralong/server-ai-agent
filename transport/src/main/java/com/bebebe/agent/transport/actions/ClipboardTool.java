package com.bebebe.agent.transport.actions;

import java.util.Optional;

public interface ClipboardTool {

    String name();

    boolean isReady();

    Optional<String> read();
}
