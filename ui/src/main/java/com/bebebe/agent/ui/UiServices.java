package com.bebebe.agent.ui;

import com.bebebe.agent.core.ActivityMonitor;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.watchdog.BuildInfo;
import com.bebebe.agent.watchdog.UpdateChecker;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;

public record UiServices(LlmProvider llm, BuildInfo build, UpdateChecker updates, BackupAction backup,
                         List<Path> diskPaths, Path configFile, IntSupplier watchdogRestarts,
                         ActivityMonitor activity) {

    @FunctionalInterface
    public interface BackupAction {
        String run(Path targetDir);
    }
}
