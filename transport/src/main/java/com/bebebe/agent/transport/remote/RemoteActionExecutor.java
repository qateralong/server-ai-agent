package com.bebebe.agent.transport.remote;

import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.ProtocolException;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.transport.actions.ActionResult;
import com.bebebe.agent.transport.messages.ErrorPayload;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RemoteActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteActionExecutor.class);

    static final Duration NETWORK_GRACE = Duration.ofSeconds(30);

    private final TransportServer server;
    private final ClientSelector selector;
    private final Duration scriptTimeout;
    private final Duration networkGrace;

    public RemoteActionExecutor(TransportServer server, ClientSelector selector, Duration scriptTimeout) {
        this(server, selector, scriptTimeout, NETWORK_GRACE);
    }

    RemoteActionExecutor(TransportServer server, ClientSelector selector, Duration scriptTimeout, Duration networkGrace) {
        this.server = server;
        this.selector = selector;
        this.scriptTimeout = scriptTimeout;
        this.networkGrace = networkGrace;
    }

    @Override
    public String name() {
        return selector.pick("scripts")
                .map(c -> "computer «" + c.name() + "»")
                .orElse("computer (not connected)");
    }

    @Override
    public Duration timeout() {
        return scriptTimeout.plus(networkGrace);
    }

    @Override
    public ActionResult run(String pythonCode) {
        if (pythonCode == null || pythonCode.isBlank()) {
            return ActionResult.launchFailed("Пустой код скрипта");
        }
        Optional<TransportServer.ClientInfo> target = selector.pick("scripts");
        if (target.isEmpty()) {
            log.atWarn().addKeyValue("event", "remote.no_client").addKeyValue("what", "script")
                    .log("Nowhere to send the script: {}", ClientSelector.noClientMessage());
            return ActionResult.launchFailed(ClientSelector.noClientMessage());
        }
        String clientId = target.get().clientId();
        Envelope request = Envelope.of(MessageType.RUN_SCRIPT_REQUEST,
                new RunScriptRequest(pythonCode, Map.of(), (int) scriptTimeout.toSeconds()));
        log.atInfo().addKeyValue("event", "remote.script.sent").addKeyValue("client_id", clientId)
                .addKeyValue("request_id", request.id())
                .log("Script sent to «{}»", target.get().name());

        long started = System.nanoTime();
        CompletableFuture<Envelope> future;
        try {
            future = server.request(clientId, request, timeout());
        } catch (RuntimeException e) {
            return ActionResult.launchFailed("Не отправить скрипт на компьютер: " + e.getMessage());
        }
        Envelope reply;
        try {
            reply = future.get(timeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            log.atWarn().addKeyValue("event", "remote.script.interrupted").log("Waiting for the result was interrupted");
            return ActionResult.launchFailed("Выполнение прервано: агент останавливается");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                log.atWarn().addKeyValue("event", "remote.script.timeout").addKeyValue("client_id", clientId)
                        .log("Computer «{}» did not return a result within {} s", target.get().name(), timeout().toSeconds());
                return new ActionResult(ActionResult.TIMEOUT_EXIT_CODE, "",
                        "Компьютер не ответил за " + timeout().toSeconds() + " с", elapsed(started), java.util.List.of());
            }
            return ActionResult.launchFailed("Связь с компьютером оборвалась: " + cause.getMessage());
        } catch (TimeoutException | CancellationException e) {
            return new ActionResult(ActionResult.TIMEOUT_EXIT_CODE, "", "Компьютер не ответил вовремя",
                    elapsed(started), java.util.List.of());
        }
        return toResult(reply, started);
    }

    private static ActionResult toResult(Envelope reply, long started) {
        if (reply.type() == MessageType.ERROR) {
            ErrorPayload error = safeError(reply);
            return ActionResult.launchFailed("Компьютер отказался выполнять скрипт: " + error.code()
                    + (error.message() == null || error.message().isBlank() ? "" : " — " + error.message()));
        }
        if (reply.type() != MessageType.RUN_SCRIPT_RESULT) {
            return ActionResult.launchFailed("Компьютер ответил не тем: " + reply.type());
        }
        try {
            RunScriptResult r = reply.payloadAs(RunScriptResult.class);
            int code = r.timeout() ? ActionResult.TIMEOUT_EXIT_CODE : r.exitCode();
            Duration duration = r.durationMs() > 0 ? Duration.ofMillis(r.durationMs()) : elapsed(started);
            return new ActionResult(code, r.stdout(), r.stderr(), duration, java.util.List.of());
        } catch (ProtocolException e) {
            return ActionResult.launchFailed("Результат с компьютера не разбирается: " + e.getMessage());
        }
    }

    private static ErrorPayload safeError(Envelope reply) {
        try {
            return reply.payloadAs(ErrorPayload.class);
        } catch (ProtocolException e) {
            return new ErrorPayload("error", reply.payload().toString());
        }
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}
