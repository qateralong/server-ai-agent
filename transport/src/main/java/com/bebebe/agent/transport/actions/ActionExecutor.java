package com.bebebe.agent.transport.actions;

import java.time.Duration;

public interface ActionExecutor {

    String name();

    Duration timeout();

    ActionResult run(String pythonCode);
}
