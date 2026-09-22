package com.bebebe.agent.stt;

import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.logging.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public final class RemoteVoiceIngest implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteVoiceIngest.class);

    private final SttConfig config;
    private final AgentSwitch agentSwitch;
    private final TranscriptionPipeline pipeline;
    private final boolean ready;

    public RemoteVoiceIngest(SttConfig config, AgentSwitch agentSwitch, Consumer<UserMessage> sink) {
        this(config, agentSwitch, sink, new WhisperTranscriber(config));
    }

    RemoteVoiceIngest(SttConfig config, AgentSwitch agentSwitch, Consumer<UserMessage> sink, Transcriber transcriber) {
        this.config = config;
        this.agentSwitch = agentSwitch;
        this.pipeline = new TranscriptionPipeline(transcriber, sink, "voice-ingest");
        Optional<String> missing = config.whatIsMissingForTranscription();
        this.ready = config.enabled() && missing.isEmpty();
        if (!config.enabled()) {
            log.info("Voice intake from clients disabled (stt.enabled = false)");
        } else if (missing.isPresent()) {
            log.warn("Voice from clients will be discarded: {}", missing.get());
        } else {
            log.info("Voice intake from clients ready: {}", config);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public boolean accept(byte[] wav, String traceId, String from) {
        String trace = traceId == null || traceId.isBlank() ? TraceContext.newId() : traceId;
        try (TraceContext.Scope ignored = TraceContext.open(trace)) {
            if (!ready) {
                log.warn("Voice from «{}» discarded: transcription not configured", from);
                return false;
            }
            if (!agentSwitch.isOn()) {
                log.info("Voice from «{}» received, but the agent is off -- not transcribing", from);
                return false;
            }
            if (wav == null || wav.length < AudioRecorder.MIN_USEFUL_BYTES) {
                log.info("Voice from «{}» too short ({} bytes) -- looks like an accidental press", from,
                        wav == null ? 0 : wav.length);
                return false;
            }
            Path file;
            try {
                file = Files.createTempFile("bebebe-voice-", ".wav");
                Files.write(file, wav);
            } catch (IOException e) {
                log.error("Cannot save received voice: {}", e.getMessage());
                return false;
            }
            log.atInfo().addKeyValue("event", "voice.received").addKeyValue("from", from)
                    .addKeyValue("bytes", wav.length)
                    .log("Voice from «{}»: {} bytes, transcribing", from, wav.length);
            pipeline.submit(file, trace);
            return true;
        }
    }

    @Override
    public void close() {
        pipeline.close();
    }
}
