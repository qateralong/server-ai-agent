package com.bebebe.agent.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class AudioRecorder implements Recorder {

    private static final Logger log = LoggerFactory.getLogger(AudioRecorder.class);

    static final long MIN_USEFUL_BYTES = 8_000;

    private final SttConfig config;

    private Process process;
    private Path target;
    private long startedAtNanos;

    public AudioRecorder(SttConfig config) {
        this.config = config;
    }

    @Override
    public boolean isRecording() {
        Process current = process;
        return current != null && current.isAlive();
    }

    List<String> command(Path output) {
        List<String> command = new ArrayList<>();
        command.add(config.arecordBinary());
        if (!config.audioDevice().isBlank()) {
            command.add("-D");
            command.add(config.audioDevice());
        }
        command.add("-f");
        command.add("S16_LE");
        command.add("-c");
        command.add("1");
        command.add("-r");
        command.add(Integer.toString(SttConfig.SAMPLE_RATE));
        command.add("-t");
        command.add("wav");

        command.add("-d");
        command.add(Long.toString(config.maxRecording().toSeconds()));
        command.add("-q");
        command.add(output.toString());
        return command;
    }

    @Override
    public synchronized boolean start() {
        if (isRecording()) {
            log.debug("Recording already in progress, ignoring repeated press");
            return false;
        }

        try {
            target = Files.createTempFile("bebebe-voice-", ".wav");
        } catch (IOException e) {
            log.error("Cannot create temporary file for recording", e);
            return false;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(command(target));
            builder.redirectErrorStream(true);
            process = builder.start();
            startedAtNanos = System.nanoTime();
            log.info("Recording started: {}", target);
            return true;
        } catch (IOException e) {
            log.error("Cannot start {}: {}", config.arecordBinary(), e.getMessage());
            deleteQuietly(target);
            target = null;
            process = null;
            return false;
        }
    }

    @Override
    public synchronized Optional<Path> stop() {
        Process current = process;
        Path file = target;
        process = null;
        target = null;

        if (current == null || file == null) {
            return Optional.empty();
        }

        long millis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("arecord did not finish within 5 s, killing");
                current.destroyForcibly();
                current.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }

        long size = sizeOf(file);
        log.info("Recording stopped: {} ms, {} bytes", millis, size);

        if (size < MIN_USEFUL_BYTES) {
            log.info("Recording too short ({} bytes), skipping", size);
            deleteQuietly(file);
            return Optional.empty();
        }
        return Optional.of(file);
    }

    public static void discard(Path file) {
        deleteQuietly(file);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Cannot delete temporary file {}", file, e);
        }
    }
}
