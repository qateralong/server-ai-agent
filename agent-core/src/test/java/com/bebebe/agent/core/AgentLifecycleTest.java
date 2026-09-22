package com.bebebe.agent.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLifecycleTest {

    private final AgentSwitch agentSwitch = new AgentSwitch(false);
    private final List<String> events = new ArrayList<>();

    private AgentLifecycleHook recording(String name) {
        return new AgentLifecycleHook() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void onAfterStart() {
                events.add(name + ".onAfterStart(flag=" + agentSwitch.state() + ")");
            }

            @Override
            public void onBeforeStop() {
                events.add(name + ".onBeforeStop(flag=" + agentSwitch.state() + ")");
            }
        };
    }

    @Test
    void onAfterStartIsCalledWhenAgentAlreadyOn() {
        agentSwitch.lifecycle().register(recording("hook"));

        agentSwitch.turnOn();

        assertEquals(List.of("hook.onAfterStart(flag=ON)"), events);
    }

    @Test
    void onBeforeStopIsCalledWhileAgentStillOn() {

        agentSwitch.turnOn();
        agentSwitch.lifecycle().register(recording("hook"));

        agentSwitch.turnOff();

        assertEquals(List.of("hook.onBeforeStop(flag=ON)"), events);
    }

    @Test
    void listenersAreNotifiedBeforeOnAfterStart() {

        agentSwitch.addListener(state -> events.add("listener(" + state + ")"));
        agentSwitch.lifecycle().register(recording("hook"));

        agentSwitch.turnOn();

        assertEquals(List.of("listener(ON)", "hook.onAfterStart(flag=ON)"), events);
    }

    @Test
    void onStopHookRunsBeforeListeners() {
        agentSwitch.turnOn();
        agentSwitch.addListener(state -> events.add("listener(" + state + ")"));
        agentSwitch.lifecycle().register(recording("hook"));

        agentSwitch.turnOff();

        assertEquals(List.of("hook.onBeforeStop(flag=ON)", "listener(OFF)"), events);
    }

    @Test
    void hooksRunInRegistrationOrder() {
        agentSwitch.lifecycle().register(recording("first"));
        agentSwitch.lifecycle().register(recording("second"));

        agentSwitch.turnOn();

        assertEquals(
                List.of("first.onAfterStart(flag=ON)", "second.onAfterStart(flag=ON)"),
                events);
    }

    @Test
    void failingHookDoesNotCancelSwitchOrBlockOthers() {

        agentSwitch.lifecycle().register(new AgentLifecycleHook() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public void onAfterStart() {
                throw new IllegalStateException("hook failed");
            }
        });
        agentSwitch.lifecycle().register(recording("next"));

        agentSwitch.turnOn();

        assertTrue(agentSwitch.isOn());
        assertEquals(List.of("next.onAfterStart(flag=ON)"), events);
    }

    @Test
    void repeatedStartDoesNotInvokeHooks() {
        agentSwitch.lifecycle().register(recording("hook"));

        agentSwitch.turnOn();
        agentSwitch.turnOn();

        assertEquals(1, events.size());
    }

    @Test
    void removedHookIsNotCalledAnymore() {
        AgentLifecycleHook hook = recording("hook");
        agentSwitch.lifecycle().register(hook);

        agentSwitch.turnOn();
        agentSwitch.lifecycle().unregister(hook);
        agentSwitch.turnOff();

        assertEquals(List.of("hook.onAfterStart(flag=ON)"), events);
    }

    @Test
    void fullStartStopCycle() {
        agentSwitch.lifecycle().register(recording("hook"));

        agentSwitch.turnOn();
        agentSwitch.turnOff();

        assertEquals(
                List.of("hook.onAfterStart(flag=ON)", "hook.onBeforeStop(flag=ON)"),
                events);
        assertFalse(agentSwitch.isOn());
    }

    @Test
    void registryKnowsHookNames() {
        agentSwitch.lifecycle().register(recording("first"));
        agentSwitch.lifecycle().register(recording("second"));

        assertEquals(List.of("first", "second"), agentSwitch.lifecycle().names());
        assertEquals(2, agentSwitch.lifecycle().size());
    }
}
