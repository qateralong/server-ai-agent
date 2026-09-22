package com.bebebe.agent.transport.actions;

import java.time.Duration;
import java.util.List;

public record ActionResult(
        int exitCode,
        String stdout,
        String stderr,
        Duration duration,
        List<String> installedPackages
) {

    public static final int TIMEOUT_EXIT_CODE = -1;
    public static final int LAUNCH_FAILED_EXIT_CODE = -2;

    private static final int OUTPUT_LIMIT = 4000;

    public ActionResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        duration = duration == null ? Duration.ZERO : duration;
        installedPackages = installedPackages == null ? List.of() : List.copyOf(installedPackages);
    }

    public static ActionResult launchFailed(String reason) {
        return new ActionResult(LAUNCH_FAILED_EXIT_CODE, "", reason, Duration.ZERO, List.of());
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public boolean isTimeout() {
        return exitCode == TIMEOUT_EXIT_CODE;
    }

    public String describeForModel() {
        StringBuilder sb = new StringBuilder();
        sb.append("exit code: ").append(isTimeout() ? "timeout" : exitCode).append('\n');
        if (!stdout.isBlank()) {
            sb.append("stdout:\n").append(truncate(stdout)).append('\n');
        }
        if (!stderr.isBlank()) {
            sb.append("stderr:\n").append(truncate(stderr)).append('\n');
        }
        return sb.toString().strip();
    }

    private static String truncate(String text) {
        if (text.length() <= OUTPUT_LIMIT) {
            return text.strip();
        }

        return "...(beginning truncated)...\n" + text.substring(text.length() - OUTPUT_LIMIT).strip();
    }
}
