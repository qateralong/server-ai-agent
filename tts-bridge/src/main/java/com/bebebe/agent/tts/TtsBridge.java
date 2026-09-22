package com.bebebe.agent.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class TtsBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TtsBridge.class);

    private final TtsConfig config;
    private final boolean ready;
    private final Path workDir;

    public TtsBridge(TtsConfig config) {
        this.config = config;
        Optional<String> missing = config.missing();
        if (missing.isPresent()) {
            log.warn("TTS not started: {}", missing.get());
        } else if (!ffmpegAvailable()) {
            missing = Optional.of("ffmpeg not found (" + config.ffmpegBinary() + ")");
            log.warn("TTS not started: {}", missing.get());
        }
        this.ready = missing.isEmpty();
        Path dir;
        try {
            dir = Files.createTempDirectory("bebebe-tts-");
        } catch (IOException e) {
            dir = Path.of(System.getProperty("java.io.tmpdir"));
        }
        this.workDir = dir;
        if (ready) {
            log.info("TTS ready: voice {}, piper {}", config.voice(), config.piperBinary());
        }
    }

    public String name() {
        return "tts-bridge";
    }

    public boolean isReady() {
        return ready;
    }

    public TtsConfig config() {
        return config;
    }

    List<String> synthCommand(Path wav) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.piperBinary().toString());
        cmd.add("-m");
        cmd.add(config.modelPath().toString());
        cmd.add("-f");
        cmd.add(wav.toString());
        if (config.lengthScale() != 1.0) {
            cmd.add("--length-scale");
            cmd.add(String.valueOf(config.lengthScale()));
        }
        return cmd;
    }

    List<String> convertCommand(Path wav, Path ogg) {
        return List.of(config.ffmpegBinary(), "-y", "-loglevel", "error", "-i", wav.toString(),
                "-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", "32k", "-application", "voip",
                ogg.toString());
    }

    public Optional<Path> synthesize(String text) {
        if (!ready) {
            return Optional.empty();
        }
        String speech = SpeechText.prepare(text, config.maxChars());
        if (speech.isBlank()) {
            return Optional.empty();
        }
        long started = System.nanoTime();
        Path wav = workDir.resolve("reply-" + System.nanoTime() + ".wav");
        Path ogg = Path.of(wav + ".ogg");
        try {
            if (!run(synthCommand(wav), speech, "piper")) {
                return Optional.empty();
            }
            if (!Files.exists(wav) || Files.size(wav) < 100) {
                log.warn("Piper did not produce a WAV");
                return Optional.empty();
            }
            if (!run(convertCommand(wav, ogg), null, "ffmpeg")) {
                return Optional.empty();
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            log.atInfo().addKeyValue("event", "tts.synthesized").addKeyValue("chars", speech.length())
                    .addKeyValue("bytes", Files.size(ogg)).addKeyValue("ms", ms)
                    .log("Synthesised {} chars in {} ms", speech.length(), ms);
            return Optional.of(ogg);
        } catch (IOException e) {
            log.warn("Speech synthesis failed: {}", e.getMessage());
            return Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(wav);
            } catch (IOException ignored) {

            }
        }
    }

    private boolean run(List<String> command, String stdin, String what) throws IOException {
        Process p = new ProcessBuilder(command).start();
        if (stdin != null) {
            try (OutputStream in = p.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
                in.write('\n');
            }
        } else {
            p.getOutputStream().close();
        }

        byte[] out = p.getInputStream().readAllBytes();
        byte[] err = p.getErrorStream().readAllBytes();
        try {
            if (!p.waitFor(config.timeout().toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("{} did not finish within {} s", what, config.timeout().toSeconds());
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return false;
        }
        if (p.exitValue() != 0) {
            log.warn("{} exited with code {}: {}", what, p.exitValue(),
                    new String(err, StandardCharsets.UTF_8).strip().lines().reduce((a, b) -> b).orElse(""));
            return false;
        }
        if (out.length > 0) {
            log.debug("{} stdout: {}", what, new String(out, StandardCharsets.UTF_8).strip());
        }
        return true;
    }

    private boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder(config.ffmpegBinary(), "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        try (var files = Files.list(workDir)) {
            files.forEach(f -> f.toFile().delete());
            Files.deleteIfExists(workDir);
        } catch (IOException ignored) {

        }
    }
}
