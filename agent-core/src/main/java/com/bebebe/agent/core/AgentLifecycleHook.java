package com.bebebe.agent.core;

public interface AgentLifecycleHook {

    String name();

    default void onAfterStart() {
    }

    default void onBeforeStop() {
    }
}
