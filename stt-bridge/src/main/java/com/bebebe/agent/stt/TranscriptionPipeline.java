package com.bebebe.agent.stt;

import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.logging.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class TranscriptionPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionPipeline.class);

    private final Transcriber transcriber;
    private final Consumer<UserMessage> sink;
    private final ExecutorService worker;

    TranscriptionPipeline(Transcriber transcriber, Consumer<UserMessage> sink, String threadName) {
        this.transcriber = transcriber;
        this.sink = sink;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    void submit(Path wav, String traceId) {
        worker.execute(TraceContext.wrap(traceId, () -> transcribeAndDispatch(wav, traceId)));
    }

    private void transcribeAndDispatch(Path wav, String traceId) {
        try {
            String text = transcriber.transcribe(wav);
            if (text.isEmpty()) {
                log.info("Speech not recognised, message not sent");
                return;
            }
            log.atInfo()
                    .addKeyValue("event", "voice.transcribed")
                    .addKeyValue("text", text)
                    .log("Recognised: «{}»", text);

            sink.accept(UserMessage.voice(text, traceId));
        } catch (SttException e) {
            log.error("Transcription failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected error during transcription", e);
        } finally {
            AudioRecorder.discard(wav);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
