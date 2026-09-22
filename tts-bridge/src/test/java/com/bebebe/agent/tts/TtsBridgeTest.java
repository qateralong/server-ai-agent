package com.bebebe.agent.tts;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsBridgeTest {

    @TempDir
    Path temp;

    private Path script(String name, String body) throws IOException {
        Path p = temp.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    private TtsConfig config(Path piper, Path ffmpeg) throws IOException {
        Files.createDirectories(temp.resolve("voices"));
        Files.writeString(temp.resolve("voices/ru_RU-test-medium.onnx"), "model");
        Files.writeString(temp.resolve("voices/ru_RU-test-medium.onnx.json"), "{}");
        return TtsConfig.from(AppConfig.fromToml("""
                [tts]
                piper_binary = "%s"
                voice = "ru_RU-test-medium"
                voices_dir = "%s"
                ffmpeg_binary = "%s"
                max_chars = 500
                """.formatted(piper, temp.resolve("voices"), ffmpeg)).section(TtsConfig.SECTION));
    }

    @Test
    void textGoesToPiperStdinAndResultIsTranscodedToOgg() throws IOException {
        Path piper = script("piper", """
                cat > "%s"
                echo "$@" > "%s"
                # -f <wav>: create a file of sufficient size
                out=""; while [ $# -gt 0 ]; do [ "$1" = "-f" ] && out="$2"; shift; done
                head -c 1000 /dev/zero > "$out\"""".formatted(temp.resolve("stdin"), temp.resolve("piper-args")));
        Path ffmpeg = script("ffmpeg", """
                case "$1" in -version) exit 0;; esac
                echo "$@" > "%s"
                for last; do :; done; printf 'OggS' > "$last\"""".formatted(temp.resolve("ffmpeg-args")));

        try (TtsBridge tts = new TtsBridge(config(piper, ffmpeg))) {
            assertTrue(tts.isReady());
            Optional<Path> ogg = tts.synthesize("**Привет**, мир!");

            assertTrue(ogg.isPresent());
            assertEquals("OggS", Files.readString(ogg.get()));
            assertEquals("Привет, мир!", Files.readString(temp.resolve("stdin")).strip(), "stdin gets the cleaned text");
            String piperArgs = Files.readString(temp.resolve("piper-args"));
            assertTrue(piperArgs.contains("-m " + temp.resolve("voices/ru_RU-test-medium.onnx")), piperArgs);
            String ffmpegArgs = Files.readString(temp.resolve("ffmpeg-args"));
            assertTrue(ffmpegArgs.contains("-c:a libopus"), ffmpegArgs);
            assertTrue(ffmpegArgs.contains("-ac 1"), ffmpegArgs);
            Files.deleteIfExists(ogg.get());
        }
    }

    @Test
    void crashedPiperIsEmptyNotException() throws IOException {
        Path piper = script("piper", "echo 'boom' >&2; exit 2");
        Path ffmpeg = script("ffmpeg", "exit 0");

        try (TtsBridge tts = new TtsBridge(config(piper, ffmpeg))) {
            assertTrue(tts.synthesize("привет").isEmpty());
        }
    }

    @Test
    void withoutVoiceBridgeNotReadyAndDoesNotCrash() throws IOException {
        Path piper = script("piper", "exit 0");
        Path ffmpeg = script("ffmpeg", "exit 0");
        TtsConfig cfg = TtsConfig.from(AppConfig.fromToml("""
                [tts]
                piper_binary = "%s"
                voice = "нет-такого"
                voices_dir = "%s"
                ffmpeg_binary = "%s"
                """.formatted(piper, temp, ffmpeg)).section(TtsConfig.SECTION));

        assertTrue(cfg.missing().orElse("").contains("voice not found"), cfg.missing().toString());
        try (TtsBridge tts = new TtsBridge(cfg)) {
            assertFalse(tts.isReady());
            assertTrue(tts.synthesize("привет").isEmpty());
        }
    }

    @Test
    void synthesisCommandPassesSpeedOnlyWhenNotOne() throws IOException {
        Path piper = script("piper", "exit 0");
        Path ffmpeg = script("ffmpeg", "exit 0");
        TtsConfig base = config(piper, ffmpeg);
        TtsConfig slow = new TtsConfig(base.piperBinary(), base.voice(), base.voicesDir(), base.ffmpegBinary(),
                1.2, base.maxChars(), base.timeout(), false);

        try (TtsBridge a = new TtsBridge(base); TtsBridge b = new TtsBridge(slow)) {
            List<String> plain = a.synthCommand(temp.resolve("x.wav"));
            List<String> scaled = b.synthCommand(temp.resolve("x.wav"));
            assertFalse(plain.contains("--length-scale"));
            assertTrue(scaled.contains("--length-scale") && scaled.contains("1.2"), scaled.toString());
        }
    }
}
