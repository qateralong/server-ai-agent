package com.bebebe.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AgentSwitch {

    private static final Logger log = LoggerFactory.getLogger(AgentSwitch.class);

    private final AtomicBoolean on;
    private final List<Consumer<AgentState>> listeners = new CopyOnWriteArrayList<>();
    private final AgentLifecycle lifecycle = new AgentLifecycle();

    public AgentSwitch(boolean enabledOnStart) {
        this.on = new AtomicBoolean(enabledOnStart);
        log.info("Agent starts in state {}", state().label());
    }

    public AgentLifecycle lifecycle() {
        return lifecycle;
    }

    public AgentState state() {
        return on.get() ? AgentState.ON : AgentState.OFF;
    }

    public boolean isOn() {
        return on.get();
    }

    public void turnOn() {
        set(true);
    }

    public void turnOff() {
        set(false);
    }

    public AgentState toggle() {
        set(!on.get());
        return state();
    }

    private synchronized void set(boolean value) {
        if (on.get() == value) {
            return;
        }

        if (!value) {
            lifecycle.fireBeforeStop();
        }

        on.set(value);
        AgentState now = state();
        log.info("Agent switched: {}", now.label());

        for (Consumer<AgentState> listener : listeners) {
            try {
                listener.accept(now);
            } catch (RuntimeException e) {

                log.warn("ON/OFF listener threw an exception", e);
            }
        }

        if (value) {
            lifecycle.fireAfterStart();
        }
    }

    public void addListener(Consumer<AgentState> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<AgentState> listener) {
        listeners.remove(listener);
    }
}
