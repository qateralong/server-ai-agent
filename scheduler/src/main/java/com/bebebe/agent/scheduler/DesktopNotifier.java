package com.bebebe.agent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DesktopNotifier {

    private static final Logger log = LoggerFactory.getLogger(DesktopNotifier.class);

    private static final List<Path> CANDIDATES = List.of(
            Path.of("/usr/bin/notify-send"), Path.of("/usr/local/bin/notify-send"));

    private final Path binary;
    private final boolean enabled;

    public DesktopNotifier(boolean enabled) {
        this(enabled, find());
    }

    DesktopNotifier(boolean enabled, Path binary) {
        this.enabled = enabled;
        this.binary = binary;
        if (enabled && binary == null) {
            log.warn("notify-send not found -- no desktop notifications (package libnotify)");
        }
    }

    public boolean isAvailable() {
        return enabled && binary != null;
    }

    List<String> command(String title, String body) {
        return List.of(binary.toString(),
                "--app-name=Server AI Agent",
                "--urgency=normal",
                "--expire-time=15000",
                "--icon=appointment-soon",
                title, body);
    }

    public boolean notify(String title, String body) {
        if (!isAvailable()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(command(title, clip(body)))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("notify-send did not respond within 5 s");
                return false;
            }
            if (process.exitValue() != 0) {
                log.debug("notify-send exited with code {}", process.exitValue());
                return false;
            }
            return true;
        } catch (IOException e) {
            log.debug("notify-send failed to start: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String clip(String text) {
        return text == null ? "" : text.length() <= 400 ? text : text.substring(0, 399) + "…";
    }

    private static Path find() {
        return CANDIDATES.stream().filter(Files::isExecutable).findFirst().orElse(null);
    }
}
