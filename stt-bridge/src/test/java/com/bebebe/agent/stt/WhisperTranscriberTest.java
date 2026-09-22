package com.bebebe.agent.stt;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperTranscriberTest {

    @TempDir
    Path tempDir;

    private SttConfig configWith(Path binary) throws IOException {
        Path model = tempDir.resolve("ggml-test.bin");
        Files.writeString(model, "fake");
        return SttConfig.from(AppConfig.fromToml("""
                [stt]
                whisper_binary = "%s"
                model_path = "%s"
                language = "ru"
                threads = 3
                """.formatted(binary, model)).section(SttConfig.SECTION));
    }

    private Path fakeWhisper(String body) throws IOException {
        Path script = tempDir.resolve("whisper-cli");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    void commandContainsExpectedFlags() throws IOException {

        WhisperTranscriber transcriber = new WhisperTranscriber(configWith(Path.of("/usr/bin/whisper-cli")));

        List<String> command = transcriber.command(Path.of("/tmp/a.wav"));

        assertEquals("/usr/bin/whisper-cli", command.getFirst());
        assertTrue(command.contains("-m"));
        assertTrue(command.contains("-f"));
        assertTrue(command.contains("/tmp/a.wav"));
        assertEquals("ru", command.get(command.indexOf("-l") + 1));
        assertEquals("3", command.get(command.indexOf("-t") + 1));
        assertTrue(command.contains("-nt"), "-nt is needed, otherwise the output has timestamps");
        assertTrue(command.contains("-np"), "-np is needed, otherwise service output gets into stdout");
    }

    @Test
    void readsRecognisedText() throws IOException {
        Path script = fakeWhisper("echo ' Привет, как дела?'");

        String text = new WhisperTranscriber(configWith(script)).transcribe(tempDir.resolve("a.wav"));

        assertEquals("Привет, как дела?", text);
    }

    @Test
    void joinsSeveralSegments() throws IOException {
        Path script = fakeWhisper("printf 'Первый сегмент.\\nВторой сегмент.\\n'");

        String text = new WhisperTranscriber(configWith(script)).transcribe(tempDir.resolve("a.wav"));

        assertEquals("Первый сегмент. Второй сегмент.", text);
    }

    @Test
    void dropsSilenceAndNoiseMarkers() {

        assertEquals("", WhisperTranscriber.cleanup(List.of("[BLANK_AUDIO]")));
        assertEquals("", WhisperTranscriber.cleanup(List.of("[Музыка]", "   ", "[ tapping ]")));
        assertEquals("Реальный текст",
                WhisperTranscriber.cleanup(List.of("[BLANK_AUDIO]", "Реальный текст")));
    }

    @Test
    void stripsTimestampsIfTheyStillArrive() {
        String text = WhisperTranscriber.cleanup(List.of(
                "[00:00:00.000 --> 00:00:02.000]   Привет",
                "[00:00:02.000 --> 00:00:04.000]   мир"));

        assertEquals("Привет мир", text);
    }

    @Test
    void serviceOutputInStderrDoesNotReachTranscript() throws IOException {

        Path script = fakeWhisper("""
                echo 'read_audio_data: reading audio data from a.wav ...' >&2
                echo 'read_audio_data: trying to decode with miniaudio' >&2
                echo ' Настоящая расшифровка.'
                """);

        String text = new WhisperTranscriber(configWith(script)).transcribe(tempDir.resolve("a.wav"));

        assertEquals("Настоящая расшифровка.", text);
    }

    @Test
    void whisperCrashGivesClearError() throws IOException {
        Path script = fakeWhisper("echo 'error: failed to load model' >&2\nexit 1");

        SttException e = assertThrows(SttException.class,
                () -> new WhisperTranscriber(configWith(script)).transcribe(tempDir.resolve("a.wav")));

        assertTrue(e.getMessage().contains("code 1"), e.getMessage());
        assertTrue(e.getMessage().contains("failed to load model"), e.getMessage());
    }

    @Test
    void missingBinaryGivesHint() throws IOException {
        SttConfig config = configWith(tempDir.resolve("нет-такого-бинарника"));

        SttException e = assertThrows(SttException.class,
                () -> new WhisperTranscriber(config).transcribe(tempDir.resolve("a.wav")));

        assertTrue(e.getMessage().contains("stt.whisper_binary"), e.getMessage());
    }
}
