package com.bebebe.agent.stt;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.MessageSource;
import com.bebebe.agent.core.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteVoiceIngestTest {

    @TempDir
    Path temp;

    private SttConfig config;
    private final List<UserMessage> delivered = new CopyOnWriteArrayList<>();
    private final AtomicReference<Path> seenFile = new AtomicReference<>();
    private final Transcriber transcriber = wav -> {
        seenFile.set(wav);
        try {
            return Files.size(wav) > 0 ? "открой браузер" : "";
        } catch (IOException e) {
            throw new SttException("no file", e);
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        Path model = temp.resolve("ggml.bin");
        Files.writeString(model, "fake");

        config = SttConfig.from(AppConfig.fromToml("""
                [stt]
                hotkey_helper = "%s"
                model_path = "%s"
                whisper_binary = "/bin/true"
                """.formatted(temp.resolve("нет-хелпера"), model)).section(SttConfig.SECTION));
    }

    private boolean await(int count) throws InterruptedException {
        for (int i = 0; i < 100 && delivered.size() < count; i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return delivered.size() >= count;
    }

    @Test
    void bytesBecomeVoiceMessageWithSameTraceId() throws Exception {
        try (RemoteVoiceIngest ingest = new RemoteVoiceIngest(config, new AgentSwitch(true), delivered::add, transcriber)) {
            assertTrue(ingest.isReady(), "transcription is ready even without the evdev helper");

            assertTrue(ingest.accept(new byte[20_000], "trace-voice-1", "ноут"));
            assertTrue(await(1));
        }
        UserMessage message = delivered.getFirst();
        assertEquals(MessageSource.VOICE, message.source());
        assertEquals("открой браузер", message.text());
        assertEquals("trace-voice-1", message.traceId());
        assertFalse(Files.exists(seenFile.get()), "temporary WAV deleted after transcription");
    }

    @Test
    void shortRecordingAndDisabledAgentAreDiscarded() throws Exception {
        try (RemoteVoiceIngest ingest = new RemoteVoiceIngest(config, new AgentSwitch(true), delivered::add, transcriber)) {
            assertFalse(ingest.accept(new byte[100], "t", "ноут"), "below threshold -- accidental press");
            assertFalse(ingest.accept(null, "t", "ноут"));
        }
        try (RemoteVoiceIngest off = new RemoteVoiceIngest(config, new AgentSwitch(false), delivered::add, transcriber)) {
            assertFalse(off.accept(new byte[20_000], "t", "ноут"), "agent off -- whisper not run");
        }
        TimeUnit.MILLISECONDS.sleep(200);
        assertTrue(delivered.isEmpty());
    }

    @Test
    void withoutModelIntakeIsHonestlyNotReady() {
        SttConfig noModel = SttConfig.from(AppConfig.fromToml("""
                [stt]
                model_path = "%s"
                """.formatted(temp.resolve("нет-модели.bin"))).section(SttConfig.SECTION));
        try (RemoteVoiceIngest ingest = new RemoteVoiceIngest(noModel, new AgentSwitch(true), delivered::add, transcriber)) {
            assertFalse(ingest.isReady());
            assertFalse(ingest.accept(new byte[20_000], "t", "ноут"));
        }
    }
}
