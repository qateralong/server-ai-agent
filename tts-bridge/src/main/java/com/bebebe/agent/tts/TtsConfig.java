package com.bebebe.agent.tts;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public record TtsConfig(Path piperBinary, String voice, Path voicesDir, String ffmpegBinary,
                        double lengthScale, int maxChars, Duration timeout, boolean replyToText) {

    public static final String SECTION = "tts";
    public static final String DEFAULT_VOICE = "ru_RU-irina-medium";

    public static TtsConfig from(ConfigSection section) {
        double scale = section.doubleValue("length_scale").orElse(1.0);
        if (scale <= 0 || scale > 5) {
            throw new IllegalArgumentException("tts.length_scale must be in (0; 5]");
        }
        return new TtsConfig(
                expand(section.string("piper_binary", "~/.local/share/bebebe-agent/piper-venv/bin/piper")),
                section.string("voice", DEFAULT_VOICE).strip(),
                expand(section.string("voices_dir", "~/.local/share/bebebe-agent/piper-voices")),
                section.string("ffmpeg_binary", "ffmpeg"),
                scale,
                section.integer("max_chars", 1500),
                section.seconds("timeout_seconds", Duration.ofSeconds(60)),
                section.bool("reply_to_text", false));
    }

    public Path modelPath() {
        if (voice.endsWith(".onnx")) {
            return expand(voice);
        }
        return voicesDir.resolve(voice + ".onnx");
    }

    public Optional<String> missing() {
        if (!Files.isExecutable(piperBinary)) {
            return Optional.of("piper not found: " + piperBinary
                    + " (python3 -m venv ~/.local/share/bebebe-agent/piper-venv && …/bin/pip install piper-tts)");
        }
        if (!Files.isRegularFile(modelPath())) {
            return Optional.of("voice not found: " + modelPath()
                    + " (…/bin/python -m piper.download_voices --data-dir " + voicesDir + " " + voice + ")");
        }
        if (!Files.isRegularFile(Path.of(modelPath() + ".json"))) {
            return Optional.of("missing " + modelPath().getFileName() + ".json next to the model");
        }
        return Optional.empty();
    }

    static Path expand(String raw) {
        String s = raw.strip();
        if (s.equals("~") || s.startsWith("~/")) {
            s = System.getProperty("user.home") + s.substring(1);
        }
        return Path.of(s).toAbsolutePath().normalize();
    }
}
