package com.bebebe.agent.stt;

import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.logging.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SttBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SttBridge.class);

    private final SttConfig config;
    private final AgentSwitch agentSwitch;
    private final Consumer<UserMessage> sink;
    private final Recorder recorder;
    private final Transcriber transcriber;
    private final com.bebebe.agent.capture.HotkeyListener hotkey;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile String currentTraceId;

    private final TranscriptionPipeline pipeline;

    public SttBridge(SttConfig config, AgentSwitch agentSwitch, Consumer<UserMessage> sink) {
        this(config, agentSwitch, sink, new AudioRecorder(config), new WhisperTranscriber(config));
    }

    SttBridge(SttConfig config,
              AgentSwitch agentSwitch,
              Consumer<UserMessage> sink,
              Recorder recorder,
              Transcriber transcriber) {
        this.config = config;
        this.agentSwitch = agentSwitch;
        this.sink = sink;
        this.recorder = recorder;
        this.transcriber = transcriber;
        this.pipeline = new TranscriptionPipeline(transcriber, sink, "stt-worker");
        this.hotkey = new com.bebebe.agent.capture.HotkeyListener(config.hotkey(), this::onPress, this::onRelease);
    }

    public String name() {
        return "stt-bridge";
    }

    public boolean isReady() {
        return running.get();
    }

    public void start() {
        if (!config.enabled()) {
            log.info("STT disabled in config (stt.enabled = false)");
            return;
        }
        Optional<String> missing = config.whatIsMissing();
        if (missing.isPresent()) {
            log.warn("STT not started: {}", missing.get());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        log.info("STT starting: {}", config);
        hotkey.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        hotkey.stop();
        if (recorder.isRecording()) {
            recorder.stop().ifPresent(AudioRecorder::discard);
        }
        pipeline.close();
        log.info("STT stopped");
    }

    @Override
    public void close() {
        stop();
    }

    private void onPress() {
        if (!agentSwitch.isOn()) {

            log.info("Key pressed, but the agent is off -- recording not started");
            return;
        }
        currentTraceId = TraceContext.newId();
        try (TraceContext.Scope ignored = TraceContext.open(currentTraceId)) {
            log.atInfo().addKeyValue("event", "voice.press").log("Key pressed, starting recording");
            recorder.start();
        }
    }

    private void onRelease() {
        if (!recorder.isRecording()) {
            return;
        }
        String traceId = currentTraceId == null ? TraceContext.newId() : currentTraceId;
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            Optional<Path> recorded = recorder.stop();
            if (recorded.isEmpty()) {
                return;
            }

            pipeline.submit(recorded.get(), traceId);
        }
    }
}
