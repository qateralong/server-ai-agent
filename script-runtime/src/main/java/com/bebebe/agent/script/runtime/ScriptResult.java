package com.bebebe.agent.script.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record ScriptResult(
        int exitCode,
        String stdout,
        String stderr,
        Duration duration,
        List<String> installedPackages
) {

    public static final int TIMEOUT_EXIT_CODE = -1;

    public static final int LAUNCH_FAILED_EXIT_CODE = -2;

    public ScriptResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        installedPackages = installedPackages == null ? List.of() : List.copyOf(installedPackages);
    }

    public static ScriptResult of(int exitCode, String stdout, String stderr, Duration duration) {
        return new ScriptResult(exitCode, stdout, stderr, duration, List.of());
    }

    public static ScriptResult launchFailed(String reason) {
        return new ScriptResult(LAUNCH_FAILED_EXIT_CODE, "", reason, Duration.ZERO, List.of());
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public boolean isTimeout() {
        return exitCode == TIMEOUT_EXIT_CODE;
    }

    public ScriptResult withInstalled(List<String> packages) {
        return new ScriptResult(exitCode, stdout, stderr, duration, packages);
    }

    public com.bebebe.agent.transport.actions.ActionResult toActionResult() {
        return new com.bebebe.agent.transport.actions.ActionResult(exitCode, stdout, stderr, duration, installedPackages);
    }

    public String describeForModel() {
        return toActionResult().describeForModel();
    }

    public Optional<String> firstStderrLine() {
        return stderr.lines().filter(line -> !line.isBlank()).reduce((first, second) -> second);
    }

}
