package com.bebebe.agent.stt;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRecorderTest {

    private static SttConfig config(String extra) {
        return SttConfig.from(AppConfig.fromToml("[stt]\n" + extra).section(SttConfig.SECTION));
    }

    @Test
    void commandSetsFormatRequiredByWhisper() {

        List<String> command = new AudioRecorder(config("")).command(Path.of("/tmp/a.wav"));

        assertEquals("S16_LE", command.get(command.indexOf("-f") + 1));
        assertEquals("1", command.get(command.indexOf("-c") + 1));
        assertEquals("16000", command.get(command.indexOf("-r") + 1));
        assertEquals("wav", command.get(command.indexOf("-t") + 1));
        assertEquals("/tmp/a.wav", command.getLast());
    }

    @Test
    void durationLimitAgainstStuckKey() {
        List<String> command = new AudioRecorder(config("max_seconds = 30")).command(Path.of("/tmp/a.wav"));

        assertEquals("30", command.get(command.indexOf("-d") + 1));
    }

    @Test
    void deviceIsAddedOnlyWhenSet() {
        assertFalse(new AudioRecorder(config("")).command(Path.of("/tmp/a.wav")).contains("-D"));

        List<String> withDevice = new AudioRecorder(config("audio_device = \"hw:1,0\""))
                .command(Path.of("/tmp/a.wav"));
        assertEquals("hw:1,0", withDevice.get(withDevice.indexOf("-D") + 1));
    }

    @Test
    void stopWithoutRecordingReturnsNothing() {
        AudioRecorder recorder = new AudioRecorder(config(""));

        assertFalse(recorder.isRecording());
        assertTrue(recorder.stop().isEmpty());
    }

    @Test
    void configReportsWhatIsMissing() {

        String missing = config("").whatIsMissing().orElse("");

        assertTrue(missing.contains("evdev helper"), missing);
    }
}
