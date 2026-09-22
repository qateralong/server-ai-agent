package com.bebebe.agent.telegram.input;

import com.bebebe.agent.telegram.menu.CallbackData;

public record InputOutcome(boolean accepted, String message, CallbackData returnTo) {

    public static InputOutcome accepted(String message, CallbackData returnTo) {
        return new InputOutcome(true, message, returnTo);
    }

    public static InputOutcome accepted(String message) {
        return new InputOutcome(true, message, null);
    }

    public static InputOutcome rejected(String message) {
        return new InputOutcome(false, message, null);
    }
}
