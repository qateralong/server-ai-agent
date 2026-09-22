package com.bebebe.agent.telegram;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.memory.MemoryConfig;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.script.library.LibraryConfig;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.menu.MenuController;

import java.nio.file.Path;

public final class TestLibrary {

    public static final MenuController.ConfirmationActions NO_CONFIRM =
            new MenuController.ConfirmationActions() {
                @Override
                public AgentReply confirm(String token) {
                    return AgentReply.text("confirmed");
                }

                @Override
                public AgentReply cancel(String token) {
                    return AgentReply.text("cancelled");
                }

                @Override
                public AgentReply trust(long scriptId) {
                    return AgentReply.text("trusted");
                }
            };

    public static final MenuController.MemoryActions NO_MEMORY_ACTIONS =
            (token, same) -> AgentReply.text("resolved:" + same);

    private TestLibrary() {
    }

    public static MemoryStore memoryIn(Path dir) {
        return new MemoryStore(MemoryConfig.from(AppConfig.fromToml("""
                [memory]
                db_path = "%s"
                """.formatted(dir.resolve("memory.db"))).section(MemoryConfig.SECTION)));
    }

    public static ScriptLibrary inDirectory(Path dir) {
        return new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(dir.resolve("library.db"), dir.resolve("scripts")))
                .section(LibraryConfig.SECTION)));
    }
}
