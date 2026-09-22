package com.bebebe.agent.stt;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public record SttConfig(
        boolean enabled,
        Path hotkeyHelper,
        String hotkeyKey,
        String hotkeyDevice,
        String arecordBinary,
        String audioDevice,
        Path whisperBinary,
        Path modelPath,
        String language,
        int threads,
        String ffmpegBinary,
        Duration maxRecording
) {

    public static final String SECTION = "stt";

    public static final int SAMPLE_RATE = 16_000;

    public static final Duration DEFAULT_MAX_RECORDING = Duration.ofSeconds(120);

    public SttConfig {
        if (threads < 1) {
            throw new IllegalArgumentException("stt.threads must be >= 1");
        }
        if (maxRecording == null || maxRecording.isZero() || maxRecording.isNegative()) {
            throw new IllegalArgumentException("stt.max_seconds must be > 0");
        }
    }

    public static SttConfig from(ConfigSection section) {
        return new SttConfig(
                section.bool("enabled", true),
                expand(section.string("hotkey_helper", "native/evdev-hotkey/build/evdev-hotkey")),
                section.string("hotkey_key", "KEY_HOME"),
                section.string("hotkey_device", ""),
                section.string("arecord_binary", "arecord"),
                section.string("audio_device", ""),
                expand(section.string("whisper_binary", "whisper-cli")),
                expand(section.string("model_path", "")),
                section.string("language", "ru"),
                section.integer("threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2)),
                section.string("ffmpeg_binary", "ffmpeg"),
                section.seconds("max_seconds", DEFAULT_MAX_RECORDING));
    }

    static Path expand(String raw) {
        if (raw == null || raw.isBlank()) {
            return Path.of("");
        }
        String value = raw.trim();
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }

    public Optional<String> whatIsMissing() {
        Optional<String> hotkey = hotkey().whatIsMissing();
        return hotkey.isPresent() ? hotkey : whatIsMissingForTranscription();
    }

    public com.bebebe.agent.capture.HotkeyConfig hotkey() {
        return new com.bebebe.agent.capture.HotkeyConfig(hotkeyHelper, hotkeyKey, hotkeyDevice);
    }

    public Optional<String> whatIsMissingForTranscription() {
        if (modelPath.toString().isEmpty()) {
            return Optional.of("stt.model_path is not set -- path to the whisper.cpp ggml model");
        }
        if (!Files.isReadable(modelPath)) {
            return Optional.of("model not found: " + modelPath.toAbsolutePath());
        }
        if (whisperBinary.toString().isEmpty()) {
            return Optional.of("stt.whisper_binary is not set");
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "SttConfig[key=%s, device=%s, whisper=%s, model=%s, language=%s, threads=%d]".formatted(
                hotkeyKey,
                hotkeyDevice.isEmpty() ? "all" : hotkeyDevice,
                whisperBinary.getFileName(),
                modelPath.getFileName(),
                language,
                threads);
    }
}
