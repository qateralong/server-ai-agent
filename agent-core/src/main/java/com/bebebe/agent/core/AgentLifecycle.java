package com.bebebe.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class AgentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycle.class);

    private static final long SLOW_MILLIS = 500;

    private final List<AgentLifecycleHook> hooks = new CopyOnWriteArrayList<>();

    public void register(AgentLifecycleHook hook) {
        hooks.add(hook);
        log.debug("Lifecycle hook registered: {}", hook.name());
    }

    public void unregister(AgentLifecycleHook hook) {
        hooks.remove(hook);
    }

    public List<String> names() {
        return hooks.stream().map(AgentLifecycleHook::name).toList();
    }

    public int size() {
        return hooks.size();
    }

    void fireAfterStart() {
        fire("onAfterStart", AgentLifecycleHook::onAfterStart);
    }

    void fireBeforeStop() {
        fire("onBeforeStop", AgentLifecycleHook::onBeforeStop);
    }

    private void fire(String phase, Consumer<AgentLifecycleHook> action) {
        if (hooks.isEmpty()) {
            return;
        }
        log.debug("{}: invoking {} hook(s)", phase, hooks.size());
        for (AgentLifecycleHook hook : hooks) {
            long started = System.nanoTime();
            try {
                action.accept(hook);
            } catch (RuntimeException e) {
                log.error("Hook {}.{} failed -- switching continues", hook.name(), phase, e);
            } finally {
                long millis = (System.nanoTime() - started) / 1_000_000;
                if (millis >= SLOW_MILLIS) {
                    log.warn("Hook {}.{} took {} ms -- it delays switching", hook.name(), phase, millis);
                } else {
                    log.debug("Hook {}.{} -- {} ms", hook.name(), phase, millis);
                }
            }
        }
    }
}
