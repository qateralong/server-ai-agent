package com.bebebe.agent.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HotkeyListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HotkeyListener.class);

    private static final Duration RETRY_MIN = Duration.ofSeconds(2);
    private static final Duration RETRY_MAX = Duration.ofMinutes(1);

    static final String EVENT_DOWN = "DOWN";
    static final String EVENT_UP = "UP";

    private final HotkeyConfig config;
    private final Runnable onPress;
    private final Runnable onRelease;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Process process;
    private Thread thread;

    public HotkeyListener(HotkeyConfig config, Runnable onPress, Runnable onRelease) {
        this.config = config;
        this.onPress = onPress;
        this.onRelease = onRelease;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, "hotkey-listener");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Process current = process;
        if (current != null) {
            current.destroy();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public List<String> command() {
        List<String> command = new ArrayList<>();
        command.add(config.helper().toString());
        command.add("--key");
        command.add(config.key());
        if (!config.device().isBlank()) {

            command.add(config.device());
        }
        return command;
    }

    private void loop() {
        Duration backoff = RETRY_MIN;
        while (running.get()) {
            try {
                int exitCode = runHelperOnce();
                if (!running.get()) {
                    break;
                }
                log.warn("Hotkey helper exited with code {}, restarting in {} s",
                        exitCode, backoff.toSeconds());
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
                log.error("Cannot start hotkey helper ({}): {}",
                        config.helper().toAbsolutePath(), e.getMessage());
            }

            if (!sleep(backoff)) {
                break;
            }
            backoff = backoff.multipliedBy(2).compareTo(RETRY_MAX) > 0 ? RETRY_MAX : backoff.multipliedBy(2);
        }
        log.debug("Hotkey listener stopped");
    }

    private int runHelperOnce() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command());

        builder.redirectErrorStream(false);

        Process started = builder.start();
        process = started;
        log.info("Hotkey helper started: {}", String.join(" ", command()));

        Thread stderrPump = new Thread(() -> pumpStderr(started), "hotkey-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(line.strip());
            }
        }

        try {
            return started.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.destroy();
            return -1;
        } finally {
            process = null;
        }
    }

    private void dispatch(String line) {
        if (line.isEmpty()) {
            return;
        }
        switch (line) {
            case EVENT_DOWN -> fire(onPress, EVENT_DOWN);
            case EVENT_UP -> fire(onRelease, EVENT_UP);
            default -> log.debug("Helper sent an unknown line: {}", line);
        }
    }

    private void fire(Runnable action, String event) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Handler of event {} failed", event, e);
        }
    }

    private void pumpStderr(Process started) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[evdev-hotkey] {}", line);
            }
        } catch (IOException e) {
            log.debug("Helper stderr stream closed", e);
        }
    }

    private boolean sleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
