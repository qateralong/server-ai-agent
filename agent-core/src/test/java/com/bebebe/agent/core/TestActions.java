package com.bebebe.agent.core;

import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.transport.actions.ActionResult;

import java.time.Duration;

/** Scripts are irrelevant to these tests, so the executor simply refuses to run anything. */
final class TestActions {

    static final ActionExecutor NONE = new ActionExecutor() {
        @Override
        public ActionResult run(String pythonCode) {
            return ActionResult.launchFailed("scripts are not part of this test");
        }

        @Override
        public Duration timeout() {
            return Duration.ofSeconds(5);
        }

        @Override
        public String name() {
            return "none";
        }
    };

    private TestActions() {
    }
}
