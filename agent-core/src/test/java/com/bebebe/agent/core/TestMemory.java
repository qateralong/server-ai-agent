package com.bebebe.agent.core;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.memory.MemoryConfig;
import com.bebebe.agent.memory.MemoryStore;

import java.nio.file.Path;

final class TestMemory {

    private TestMemory() {
    }

    static MemoryStore inDirectory(Path dir) {
        return inDirectory(dir, MemoryConfig.DEFAULT_CONSOLIDATE_EVERY);
    }

    static MemoryStore inDirectory(Path dir, int consolidateEvery) {
        return new MemoryStore(MemoryConfig.from(AppConfig.fromToml("""
                [memory]
                db_path = "%s"
                consolidate_every = %d
                """.formatted(dir.resolve("memory.db"), consolidateEvery)).section(MemoryConfig.SECTION)));
    }
}
