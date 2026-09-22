package com.bebebe.agent.capture;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSoundRecorderTest {

    @Test
    void pcmIsWrappedInValidWav16kHzMono() throws Exception {
        byte[] pcm = new byte[32_000];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 251);
        }

        byte[] wav = JavaSoundRecorder.toWav(pcm);

        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            assertEquals(16_000f, in.getFormat().getSampleRate());
            assertEquals(1, in.getFormat().getChannels());
            assertEquals(16, in.getFormat().getSampleSizeInBits());
            assertEquals(16_000, in.getFrameLength());
            byte[] back = in.readAllBytes();
            assertEquals(pcm.length, back.length);
            assertEquals(pcm[12345], back[12345], "samples are not distorted");
        }
        assertEquals(1000, JavaSoundRecorder.durationMs(wav));
    }

    @Test
    void emptyRecordingIsAlsoValidWav() {
        byte[] wav = JavaSoundRecorder.toWav(new byte[0]);
        assertEquals(44, wav.length, "header only");
        assertEquals(0, JavaSoundRecorder.durationMs(wav));
    }

    @Test
    void withoutMicrophoneDiagnosticsAreHonestNotException() {
        JavaSoundRecorder recorder = new JavaSoundRecorder("нет-такого-устройства-zzz", java.time.Duration.ofSeconds(5));
        var missing = recorder.whatIsMissing();
        assertTrue(missing.isPresent());
        assertTrue(missing.get().contains("нет-такого-устройства-zzz"), missing.get());
        assertNotNull(JavaSoundRecorder.inputDevices());
        assertTrue(recorder.stop().isEmpty(), "stop without start -- empty");
    }
}
