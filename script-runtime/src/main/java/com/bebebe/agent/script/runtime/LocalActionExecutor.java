package com.bebebe.agent.script.runtime;

import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.transport.actions.ActionResult;

import java.time.Duration;

public final class LocalActionExecutor implements ActionExecutor {

    static final Duration INSTALL_GRACE = Duration.ofSeconds(90);

    private final ScriptRuntime runtime;

    public LocalActionExecutor(ScriptRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public Duration timeout() {
        return runtime.config().timeout().plus(INSTALL_GRACE);
    }

    @Override
    public ActionResult run(String pythonCode) {
        return runtime.run(pythonCode).toActionResult();
    }

    public ScriptRuntime runtime() {
        return runtime;
    }
}
