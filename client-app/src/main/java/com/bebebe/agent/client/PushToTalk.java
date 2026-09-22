package com.bebebe.agent.client;

import com.bebebe.agent.capture.HotkeyConfig;
import com.bebebe.agent.capture.HotkeyListener;
import com.bebebe.agent.capture.JavaSoundRecorder;
import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.messages.VoiceAudioPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.LineUnavailableException;
import java.time.Duration;
import java.util.Optional;

public final class PushToTalk implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PushToTalk.class);

    static final int MIN_WAV_BYTES = 8_000;

    private final HotkeyConfig hotkeyConfig;
    private final JavaSoundRecorder recorder;
    private final HotkeyListener hotkey;
    private volatile com.bebebe.agent.transport.TransportClient transport;
    private volatile String currentTraceId;
    private volatile boolean enabled;

    PushToTalk(HotkeyConfig hotkeyConfig, String audioDevice, Duration maxRecording) {
        this.hotkeyConfig = hotkeyConfig;
        this.recorder = new JavaSoundRecorder(audioDevice, maxRecording);
        this.hotkey = new HotkeyListener(hotkeyConfig, this::onPress, this::onRelease);
    }

    boolean start() {
        Optional<String> missing = hotkeyConfig.whatIsMissing().or(recorder::whatIsMissing);
        if (missing.isPresent()) {
            log.warn("Push-to-talk not started: {}", missing.get());
            return false;
        }
        hotkey.start();
        enabled = true;
        log.info("Push-to-talk started: key {}, recording device {}", hotkeyConfig.key(),
                recorder.whatIsMissing().isEmpty() ? "ready" : "?");
        return true;
    }

    boolean isEnabled() {
        return enabled;
    }

    void attach(com.bebebe.agent.transport.TransportClient client) {
        this.transport = client;
    }

    private void sendVia(Envelope envelope) {
        com.bebebe.agent.transport.TransportClient client = transport;
        if (client == null) {
            throw new IllegalStateException("transport not connected yet");
        }
        client.send(envelope);
    }

    private void onPress() {
        currentTraceId = TraceContext.newId();
        try (TraceContext.Scope ignored = TraceContext.open(currentTraceId)) {
            try {
                recorder.start();
                log.atInfo().addKeyValue("event", "voice.press").log("Key pressed, recording the microphone");
            } catch (LineUnavailableException e) {
                log.error("Cannot open the microphone: {}", e.getMessage());
            }
        }
    }

    private void onRelease() {
        String traceId = currentTraceId == null ? TraceContext.newId() : currentTraceId;
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            Optional<byte[]> wav = recorder.stop();
            if (wav.isEmpty()) {
                return;
            }
            send(wav.get(), traceId);
        }
    }

    public void send(byte[] wav, String traceId) {
        if (wav.length < MIN_WAV_BYTES) {
            log.info("Recording too short ({} bytes) -- not sending", wav.length);
            return;
        }
        long durationMs = JavaSoundRecorder.durationMs(wav);
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {

            sendVia(Envelope.of(MessageType.VOICE_AUDIO_PUSH, VoiceAudioPush.wav(wav, durationMs)));
            log.atInfo().addKeyValue("event", "voice.sent").addKeyValue("bytes", wav.length)
                    .addKeyValue("duration_ms", durationMs)
                    .log("Voice sent to the server: {} bytes, {} ms", wav.length, durationMs);
        } catch (RuntimeException e) {
            log.warn("Voice not sent (no connection to the server?): {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        hotkey.close();
        recorder.stop();
    }
}
