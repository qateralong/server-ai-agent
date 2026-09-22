package com.bebebe.agent.clipboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class ClipboardBridge {

    private static final Logger log = LoggerFactory.getLogger(ClipboardBridge.class);
    private static final int TIMEOUT_SECONDS = 3;

    private final List<String> baseCommand;
    private final boolean available;

    public ClipboardBridge() {
        this(List.of("wl-paste"));
    }

    public ClipboardBridge(List<String> baseCommand) {
        this.baseCommand = List.copyOf(baseCommand);
        this.available = probe();
        if (!available) {
            log.warn("wl-paste not found -- clipboard reading unavailable (package wl-clipboard)");
        }
    }

    public String name() {
        return "clipboard-bridge";
    }

    public boolean isReady() {
        return available;
    }

    List<String> readCommand() {
        List<String> cmd = new java.util.ArrayList<>(baseCommand);
        cmd.add("--no-newline");
        cmd.add("--type");
        cmd.add("text");
        return cmd;
    }

    public Optional<String> read() {
        if (!available) {
            return Optional.empty();
        }
        try {
            Process p = new ProcessBuilder(readCommand())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("wl-paste did not respond within {} s", TIMEOUT_SECONDS);
                return Optional.empty();
            }
            if (p.exitValue() != 0) {

                return Optional.empty();
            }
            String text = new String(out, StandardCharsets.UTF_8);
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (IOException e) {
            log.warn("Cannot read clipboard: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private boolean probe() {
        try {
            List<String> cmd = new java.util.ArrayList<>(baseCommand);
            cmd.add("--version");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
