package com.bebebe.agent.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class WhisperTranscriber implements Transcriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperTranscriber.class);

    private static final Pattern NOISE = Pattern.compile("^\\[[^]]*]$");

    private static final Pattern TIMESTAMP = Pattern.compile("^\\[[0-9:.\\s\\->]+]\\s*");

    private static final long TIMEOUT_MULTIPLIER = 3;
    private static final long TIMEOUT_MIN_SECONDS = 60;

    private final SttConfig config;

    public WhisperTranscriber(SttConfig config) {
        this.config = config;
    }

    List<String> command(Path wav) {
        List<String> command = new ArrayList<>();
        command.add(config.whisperBinary().toString());
        command.add("-m");
        command.add(config.modelPath().toString());
        command.add("-f");
        command.add(wav.toString());
        command.add("-l");
        command.add(config.language());
        command.add("-t");
        command.add(Integer.toString(config.threads()));
        command.add("-nt");
        command.add("-np");
        return command;
    }

    @Override
    public String transcribe(Path wav) {
        long started = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command(wav));
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SttException("Cannot start whisper.cpp (" + config.whisperBinary().toAbsolutePath()
                    + "). Check stt.whisper_binary", e);
        }

        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        Thread errorPump = new Thread(() -> readAll(process.getErrorStream(), stderr), "whisper-stderr");
        errorPump.setDaemon(true);
        errorPump.start();

        readAll(process.getInputStream(), stdout);

        long timeout = Math.max(TIMEOUT_MIN_SECONDS, config.maxRecording().toSeconds() * TIMEOUT_MULTIPLIER);
        int exitCode;
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SttException("whisper.cpp did not respond within " + timeout + " s");
            }
            exitCode = process.exitValue();
            errorPump.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SttException("Transcription interrupted", e);
        }

        if (exitCode != 0) {
            throw new SttException("whisper.cpp exited with code " + exitCode + ": " + tail(stderr));
        }

        String text = cleanup(stdout);
        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Transcribed in {} ms: {}", millis,
                text.isEmpty() ? "<speech not recognised>" : "«" + text + "»");
        return text;
    }

    static String cleanup(List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String raw : lines) {
            String line = TIMESTAMP.matcher(raw.strip()).replaceFirst("").strip();
            if (line.isEmpty() || NOISE.matcher(line).matches()) {
                continue;
            }
            kept.add(line);
        }
        return String.join(" ", kept).replaceAll("\\s{2,}", " ").strip();
    }

    private static void readAll(java.io.InputStream stream, List<String> target) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.add(line);
            }
        } catch (IOException e) {
            log.debug("whisper.cpp stream closed", e);
        }
    }

    private static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 5);
        return String.join(" | ", lines.subList(from, lines.size()));
    }
}
