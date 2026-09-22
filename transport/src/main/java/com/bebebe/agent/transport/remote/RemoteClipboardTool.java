package com.bebebe.agent.transport.remote;

import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.ProtocolException;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.actions.ClipboardTool;
import com.bebebe.agent.transport.messages.ClipboardRequest;
import com.bebebe.agent.transport.messages.ClipboardResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class RemoteClipboardTool implements ClipboardTool {

    private static final Logger log = LoggerFactory.getLogger(RemoteClipboardTool.class);
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final TransportServer server;
    private final ClientSelector selector;

    public RemoteClipboardTool(TransportServer server, ClientSelector selector) {
        this.server = server;
        this.selector = selector;
    }

    @Override
    public String name() {
        return selector.pick("clipboard")
                .map(c -> "computer «" + c.name() + "»")
                .orElse("computer (not connected)");
    }

    @Override
    public boolean isReady() {
        return selector.pick("clipboard").isPresent();
    }

    @Override
    public Optional<String> read() {
        Optional<TransportServer.ClientInfo> target = selector.pick("clipboard");
        if (target.isEmpty()) {
            log.atWarn().addKeyValue("event", "remote.no_client").addKeyValue("what", "clipboard")
                    .log("No one to read the clipboard from: {}", ClientSelector.noClientMessage());
            return Optional.empty();
        }
        try {
            Envelope reply = server.request(target.get().clientId(),
                    Envelope.of(MessageType.CLIPBOARD_REQUEST, ClipboardRequest.standard()), TIMEOUT)
                    .get(TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (reply.type() != MessageType.CLIPBOARD_RESULT) {
                log.warn("Computer «{}» answered the clipboard request with {}", target.get().name(), reply.type());
                return Optional.empty();
            }
            ClipboardResult result = reply.payloadAs(ClipboardResult.class);
            if (!result.available()) {
                log.info("Clipboard on «{}» is empty or non-text: {}", target.get().name(), result.error());
                return Optional.empty();
            }
            log.atInfo().addKeyValue("event", "remote.clipboard").addKeyValue("client_id", target.get().clientId())
                    .addKeyValue("chars", result.text().length()).addKeyValue("truncated", result.truncated())
                    .log("Clipboard read on «{}»: {} chars", target.get().name(), result.text().length());
            return Optional.of(result.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
            log.warn("Clipboard from «{}» not received: {}", target.get().name(), e.getMessage());
            return Optional.empty();
        }
    }
}
