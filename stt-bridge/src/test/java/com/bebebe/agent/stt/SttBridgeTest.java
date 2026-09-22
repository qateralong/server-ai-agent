package com.bebebe.agent.stt;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.MessageSource;
import com.bebebe.agent.core.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SttBridgeTest {

    @TempDir
    Path tempDir;

    private final List<UserMessage> delivered = new CopyOnWriteArrayList<>();
    private AgentSwitch agentSwitch;
    private SttConfig config;

    private final class FakeRecorder implements Recorder {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean recording;
        volatile boolean produceFile = true;

        @Override
        public synchronized boolean start() {
            starts.incrementAndGet();
            recording = true;
            return true;
        }

        @Override
        public synchronized Optional<Path> stop() {
            stops.incrementAndGet();
            recording = false;
            if (!produceFile) {
                return Optional.empty();
            }
            try {
                Path wav = Files.createTempFile(tempDir, "voice-", ".wav");
                Files.writeString(wav, "не настоящий wav");
                return Optional.of(wav);
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean isRecording() {
            return recording;
        }
    }

    private static final class FakeTranscriber implements Transcriber {
        volatile String result = "привет агент";
        volatile RuntimeException failure;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String transcribe(Path wav) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private FakeRecorder recorder;
    private FakeTranscriber transcriber;

    @BeforeEach
    void setUp() throws IOException {
        agentSwitch = new AgentSwitch(true);

        Path helper = tempDir.resolve("evdev-hotkey");
        Files.writeString(helper, "#!/bin/sh\nprintf 'DOWN\\n'\nsleep 0.2\nprintf 'UP\\n'\nsleep 5\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwxr-xr-x"));

        Path model = tempDir.resolve("ggml-test.bin");
        Files.writeString(model, "fake");

        config = SttConfig.from(AppConfig.fromToml("""
                [stt]
                hotkey_helper = "%s"
                model_path = "%s"
                whisper_binary = "/bin/true"
                language = "ru"
                """.formatted(helper, model)).section(SttConfig.SECTION));

        recorder = new FakeRecorder();
        transcriber = new FakeTranscriber();
    }

    private SttBridge bridge() {
        return new SttBridge(config, agentSwitch, delivered::add, recorder, transcriber);
    }

    private boolean await(int count) throws InterruptedException {
        for (int i = 0; i < 100 && delivered.size() < count; i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return delivered.size() >= count;
    }

    @Test
    void fullPathFromPressToUserMessage() throws Exception {
        try (SttBridge bridge = bridge()) {
            bridge.start();
            assertTrue(await(1), "message did not arrive");
        }

        UserMessage message = delivered.getFirst();
        assertEquals(MessageSource.VOICE, message.source());
        assertEquals("привет агент", message.text());

        assertTrue(message.replyTarget().isEmpty());
        assertEquals(1, recorder.starts.get());
        assertEquals(1, recorder.stops.get());
    }

    @Test
    void disabledAgentDoesNotStartRecording() throws Exception {

        agentSwitch.turnOff();

        try (SttBridge bridge = bridge()) {
            bridge.start();
            TimeUnit.MILLISECONDS.sleep(700);
        }

        assertEquals(0, recorder.starts.get());
        assertEquals(0, transcriber.calls.get());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void tooShortRecordingDoesNotGoToWhisper() throws Exception {
        recorder.produceFile = false;

        try (SttBridge bridge = bridge()) {
            bridge.start();
            TimeUnit.MILLISECONDS.sleep(700);
        }

        assertEquals(1, recorder.starts.get());
        assertEquals(0, transcriber.calls.get());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void emptyTranscriptIsNotSent() throws Exception {

        transcriber.result = "";

        try (SttBridge bridge = bridge()) {
            bridge.start();
            TimeUnit.MILLISECONDS.sleep(700);
        }

        assertEquals(1, transcriber.calls.get());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void transcriptionErrorDoesNotBreakBridge() throws Exception {
        transcriber.failure = new SttException("model failed to load");

        try (SttBridge bridge = bridge()) {
            bridge.start();
            TimeUnit.MILLISECONDS.sleep(700);
            assertTrue(bridge.isReady(), "the bridge must stay alive");
        }

        assertTrue(delivered.isEmpty());
    }

    @Test
    void temporaryFileIsDeletedAfterTranscription() throws Exception {
        try (SttBridge bridge = bridge()) {
            bridge.start();
            assertTrue(await(1));
            TimeUnit.MILLISECONDS.sleep(200);
        }

        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".wav")),
                    "temporary WAV not deleted");
        }
    }

    @Test
    void withoutModelBridgeDoesNotStart() {
        SttConfig broken = SttConfig.from(AppConfig.fromToml("""
                [stt]
                hotkey_helper = "%s"
                model_path = "/нет/такой/модели.bin"
                """.formatted(config.hotkeyHelper())).section(SttConfig.SECTION));

        try (SttBridge bridge = new SttBridge(broken, agentSwitch, delivered::add, recorder, transcriber)) {
            bridge.start();
            assertFalse(bridge.isReady());
        }
        assertTrue(broken.whatIsMissing().orElse("").contains("model not found"),
                broken.whatIsMissing().orElse("<empty>"));
    }

    @Test
    void bridgeDisabledInConfigDoesNotStart() {
        SttConfig disabled = SttConfig.from(AppConfig.fromToml("""
                [stt]
                enabled = false
                """).section(SttConfig.SECTION));

        try (SttBridge bridge = new SttBridge(disabled, agentSwitch, delivered::add, recorder, transcriber)) {
            bridge.start();
            assertFalse(bridge.isReady());
        }
    }
}
