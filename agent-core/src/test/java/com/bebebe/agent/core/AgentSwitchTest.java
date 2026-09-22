package com.bebebe.agent.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSwitchTest {

    @Test
    void startsInGivenState() {
        assertTrue(new AgentSwitch(true).isOn());
        assertFalse(new AgentSwitch(false).isOn());
    }

    @Test
    void togglesBackAndForth() {
        AgentSwitch agentSwitch = new AgentSwitch(false);

        assertEquals(AgentState.ON, agentSwitch.toggle());
        assertEquals(AgentState.OFF, agentSwitch.toggle());
    }

    @Test
    void notifiesListenersOnlyOnRealChange() {
        AgentSwitch agentSwitch = new AgentSwitch(false);
        List<AgentState> seen = new ArrayList<>();
        agentSwitch.addListener(seen::add);

        agentSwitch.turnOff();
        agentSwitch.turnOn();
        agentSwitch.turnOn();
        agentSwitch.turnOff();

        assertEquals(List.of(AgentState.ON, AgentState.OFF), seen);
    }

    @Test
    void listenerExceptionDoesNotBreakSwitching() {
        AgentSwitch agentSwitch = new AgentSwitch(false);
        List<AgentState> seen = new ArrayList<>();
        agentSwitch.addListener(state -> {
            throw new IllegalStateException("listener failed");
        });
        agentSwitch.addListener(seen::add);

        agentSwitch.turnOn();

        assertTrue(agentSwitch.isOn());
        assertEquals(List.of(AgentState.ON), seen);
    }

    @Test
    void unsubscribedListenerIsNotCalled() {
        AgentSwitch agentSwitch = new AgentSwitch(false);
        List<AgentState> seen = new ArrayList<>();
        java.util.function.Consumer<AgentState> listener = seen::add;

        agentSwitch.addListener(listener);
        agentSwitch.turnOn();
        agentSwitch.removeListener(listener);
        agentSwitch.turnOff();

        assertEquals(List.of(AgentState.ON), seen);
    }
}
