package com.bebebe.agent.transport.remote;

import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.TransportClient;
import com.bebebe.agent.transport.TransportConfig;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.actions.ActionResult;
import com.bebebe.agent.transport.messages.ClipboardResult;
import com.bebebe.agent.transport.messages.ErrorPayload;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import com.bebebe.agent.transport.messages.StatusPush;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteExecutorsTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @TempDir
    Path temp;

    private TransportServer server;
    private PairingTokens tokens;
    private TransportClient client;

    @BeforeEach
    void setUp() {
        TransportConfig config = TransportConfig.inDirectory(temp, 0, Duration.ofSeconds(1), Duration.ofSeconds(2));
        tokens = new PairingTokens(config.clientsFile());
        server = new TransportServer(config, tokens);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    private void connect(Function<Envelope, Optional<Envelope>> onRequest, List<String> capabilities) throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        client = new TransportClient(TransportClient.Config.of(URI.create("wss://127.0.0.1:" + server.port()),
                server.fingerprint(), pairing.clientId(), "ноут", pairing.token()), new TransportClient.Handler() {
            @Override
            public Optional<Envelope> onRequest(Envelope request) {
                return onRequest.apply(request);
            }

            @Override
            public StatusPush status() {
                return new StatusPush("ноут", "Arch", "я", "wayland-0", capabilities, "t", 1);
            }
        });
        client.start();
        assertTrue(client.awaitConnected(WAIT));
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && (server.clients().isEmpty() || server.clients().getFirst().status() == null)) {
            Thread.sleep(20);
        }
    }

    private RemoteActionExecutor executor() {
        return new RemoteActionExecutor(server, new ClientSelector(server, ""), Duration.ofSeconds(2));
    }

    @Test
    void withoutClientErrorIsImmediate() {
        long started = System.nanoTime();
        ActionResult result = executor().run("print(1)");
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertFalse(result.isSuccess());
        assertEquals(ActionResult.LAUNCH_FAILED_EXIT_CODE, result.exitCode());
        assertTrue(result.stderr().contains("нет связи с компьютером"), result.stderr());
        assertTrue(ms < 500, "the answer is immediate, not by timeout: " + ms + " мс");
        assertEquals("computer (not connected)", executor().name());

        RemoteClipboardTool clipboard = new RemoteClipboardTool(server, new ClientSelector(server, ""));
        assertFalse(clipboard.isReady());
        assertTrue(clipboard.read().isEmpty());
    }

    @Test
    void scriptGoesToClientAndResultReturns() throws Exception {
        connect(request -> {
            RunScriptRequest r = request.payloadAs(RunScriptRequest.class);
            return Optional.of(request.reply(MessageType.RUN_SCRIPT_RESULT,
                    new RunScriptResult(0, "получено: " + r.code(), "warn", 120, false)));
        }, List.of("scripts", "clipboard"));

        RemoteActionExecutor executor = executor();
        ActionResult result = executor.run("print('hi')");

        assertTrue(result.isSuccess(), result.stderr());
        assertEquals("получено: print('hi')", result.stdout());
        assertEquals("warn", result.stderr());
        assertEquals(Duration.ofMillis(120), result.duration());
        assertEquals("computer «ноут»", executor.name());
        assertEquals(Duration.ofSeconds(2).plus(RemoteActionExecutor.NETWORK_GRACE), executor.timeout());
    }

    @Test
    void timeoutAndClientErrorArriveAsCodes() throws Exception {
        connect(request -> {
            RunScriptRequest r = request.payloadAs(RunScriptRequest.class);
            if (r.code().contains("timeout")) {
                return Optional.of(request.reply(MessageType.RUN_SCRIPT_RESULT,
                        new RunScriptResult(-1, "", "", 2000, true)));
            }
            return Optional.of(request.reply(MessageType.ERROR, new ErrorPayload("busy", "занят другим скриптом")));
        }, List.of("scripts"));

        ActionResult timeout = executor().run("timeout");
        assertTrue(timeout.isTimeout());

        ActionResult refused = executor().run("print(1)");
        assertEquals(ActionResult.LAUNCH_FAILED_EXIT_CODE, refused.exitCode());
        assertTrue(refused.stderr().contains("busy"), refused.stderr());
        assertTrue(refused.stderr().contains("занят"), refused.stderr());
    }

    @Test
    void silentClientIsTimeoutNotEternalWait() throws Exception {
        connect(request -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }, List.of("scripts"));
        RemoteActionExecutor quick = new RemoteActionExecutor(server, new ClientSelector(server, ""),
                Duration.ofMillis(200), Duration.ofMillis(500));

        long started = System.nanoTime();
        ActionResult result = quick.run("sleep");
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        assertTrue(result.isTimeout(), result.stderr());
        assertTrue(seconds <= 3, "waited " + seconds + " s -- ceiling = script timeout + network margin");
    }

    @Test
    void clipboardIsReadOnClientAndEmptinessIsHonest() throws Exception {
        connect(request -> Optional.of(request.reply(MessageType.CLIPBOARD_RESULT,
                ClipboardResult.of("скопировано на ноуте", false))), List.of("clipboard"));
        RemoteClipboardTool clipboard = new RemoteClipboardTool(server, new ClientSelector(server, ""));

        assertTrue(clipboard.isReady());
        assertEquals(Optional.of("скопировано на ноуте"), clipboard.read());
        assertEquals("computer «ноут»", clipboard.name());

        assertFalse(executor().run("x").isSuccess(), "a client without the scripts capability gets no scripts");
    }

    @Test
    void clientIsChosenByCapabilityAndPreference() throws Exception {
        connect(request -> Optional.empty(), List.of("clipboard"));
        ClientSelector any = new ClientSelector(server, "");
        assertTrue(any.pick("clipboard").isPresent());
        assertTrue(any.pick("scripts").isEmpty(), "capability not declared");
        assertTrue(any.pick(null).isPresent(), "without a requirement -- any");

        ClientSelector missingPreferred = new ClientSelector(server, "no-such");
        assertTrue(missingPreferred.pick("clipboard").isPresent(), "preference not found -- take whoever is there");
    }
}
