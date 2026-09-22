package com.bebebe.agent.telegram.input;

@FunctionalInterface
public interface InputHandler {

    InputOutcome accept(String value);
}
