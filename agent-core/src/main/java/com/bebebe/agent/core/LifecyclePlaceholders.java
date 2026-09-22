package com.bebebe.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LifecyclePlaceholders {

    private LifecyclePlaceholders() {
    }

    public static final class MemoryWarmup implements AgentLifecycleHook {

        private static final Logger log = LoggerFactory.getLogger(MemoryWarmup.class);

        @Override
        public String name() {
            return "memory-warmup";
        }

        @Override
        public void onAfterStart() {
            log.info("[hook {}] onAfterStart: long-term memory loading "
                    + "and persona context assembly will live here (memory-store)", name());
        }

        @Override
        public void onBeforeStop() {
            log.info("[hook {}] onBeforeStop: saving what the agent learned "
                    + "during the session will live here (memory-store)", name());
        }
    }
}
