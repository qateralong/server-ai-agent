package com.bebebe.agent.core;

public enum AgentState {

    ON,

    OFF;

    public boolean isOn() {
        return this == ON;
    }

    public String label() {
        return this == ON ? "On" : "Off";
    }
}
