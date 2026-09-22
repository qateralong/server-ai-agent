package com.bebebe.agent.client;

import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.ProtocolException;
import com.bebebe.agent.transport.TransportClient;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.transport.actions.ActionResult;
import com.bebebe.agent.transport.actions.ClipboardTool;
import com.bebebe.agent.transport.messages.ClipboardRequest;
import com.bebebe.agent.transport.messages.ClipboardResult;
import com.bebebe.agent.transport.messages.ErrorPayload;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import com.bebebe.agent.transport.messages.StatusPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

final class ClientHandler implements TransportClient.Handler {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final ActionExecutor executor;
    private final ClipboardTool clipboard;
    private final boolean voice;
    private final String version;
    private final Instant startedAt = Instant.now();
    private final Consumer<TransportClient.State> onState;

    ClientHandler(ActionExecutor executor, ClipboardTool clipboard, boolean voice, String version,
                  Consumer<TransportClient.State> onState) {
        this.executor = executor;
        this.clipboard = clipboard;
        this.voice = voice;
        this.version = version;
        this.onState = onState;
    }

    @Override
    public Optional<Envelope> onRequest(Envelope request) {
        return switch (request.type()) {
            case RUN_SCRIPT_REQUEST -> Optional.of(runScript(request));
            case CLIPBOARD_REQUEST -> Optional.of(readClipboard(request));
            default -> Optional.empty();
        };
    }

    private Envelope runScript(Envelope request) {
        RunScriptRequest r;
        try {
            r = request.payloadAs(RunScriptRequest.class);
        } catch (ProtocolException e) {
            return request.reply(MessageType.ERROR, new ErrorPayload("bad_payload", e.getMessage()));
        }
        if (!r.arguments().isEmpty()) {

            log.warn("Script arrived with arguments {} -- they are ignored for now", r.arguments().keySet());
        }
        log.atInfo().addKeyValue("event", "client.script.start").addKeyValue("request_id", request.id())
                .addKeyValue("lines", r.code().lines().count())
                .log("Server asks to run a script ({} lines, timeout {} s)", r.code().lines().count(), r.timeoutSeconds());
        ActionResult result = executor.run(r.code());
        log.atInfo().addKeyValue("event", "client.script.done").addKeyValue("request_id", request.id())
                .addKeyValue("exit_code", result.exitCode()).addKeyValue("timeout", result.isTimeout())
                .addKeyValue("duration_ms", result.duration().toMillis())
                .log("Script finished: exit={}", result.isTimeout() ? "timeout" : result.exitCode());
        return request.reply(MessageType.RUN_SCRIPT_RESULT, new RunScriptResult(
                result.exitCode(), result.stdout(), result.stderr(), result.duration().toMillis(), result.isTimeout()));
    }

    private Envelope readClipboard(Envelope request) {
        int maxChars;
        try {
            maxChars = Math.max(1, request.payloadAs(ClipboardRequest.class).maxChars());
        } catch (ProtocolException e) {
            maxChars = ClipboardRequest.standard().maxChars();
        }
        if (!clipboard.isReady()) {
            return request.reply(MessageType.CLIPBOARD_RESULT, ClipboardResult.unavailable("no wl-paste on this machine"));
        }
        Optional<String> text = clipboard.read();
        log.atInfo().addKeyValue("event", "client.clipboard").addKeyValue("available", text.isPresent())
                .log("Server asks for the clipboard: {}", text.isPresent() ? text.get().length() + " chars" : "empty");
        if (text.isEmpty()) {
            return request.reply(MessageType.CLIPBOARD_RESULT, ClipboardResult.unavailable("clipboard is empty or non-text"));
        }
        String value = text.get();
        boolean truncated = value.length() > maxChars;
        return request.reply(MessageType.CLIPBOARD_RESULT,
                ClipboardResult.of(truncated ? value.substring(0, maxChars) : value, truncated));
    }

    @Override
    public void onMessage(Envelope message) {
        if (message.type() == MessageType.ERROR) {
            log.warn("Server reported an error: {}", message.payload());
        }
    }

    @Override
    public StatusPush status() {
        List<String> caps = new ArrayList<>(List.of("scripts"));
        if (clipboard.isReady()) {
            caps.add("clipboard");
        }
        if (voice) {
            caps.add("voice");
        }
        return StatusPush.local(caps, version, startedAt);
    }

    @Override
    public void onStateChanged(TransportClient.State state) {
        onState.accept(state);
    }
}
