package com.bebebe.agent.transport;

import com.bebebe.agent.transport.messages.AuthRequest;
import com.bebebe.agent.transport.messages.ClipboardRequest;
import com.bebebe.agent.transport.messages.ClipboardResult;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import com.bebebe.agent.transport.messages.StatusPush;
import com.bebebe.agent.transport.messages.VoiceAudioPush;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRoundTripTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT = Duration.ofMillis(400);

    @TempDir
    Path temp;

    private TransportConfig config;
    private PairingTokens tokens;
    private TransportServer server;
    private TransportClient client;
    private final BlockingQueue<Envelope> serverInbox = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> connected = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> disconnected = new LinkedBlockingQueue<>();

    private final TransportServer.Listener listener = new TransportServer.Listener() {
        @Override
        public void onClientConnected(TransportServer.ClientInfo c) {
            connected.add(c.clientId());
        }

        @Override
        public void onClientDisconnected(TransportServer.ClientInfo c, int code, String reason) {
            disconnected.add(c.clientId() + ":" + code);
        }

        @Override
        public void onMessage(TransportServer.ClientInfo c, Envelope message) {
            serverInbox.add(message);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        config = TransportConfig.inDirectory(temp, port, HEARTBEAT, Duration.ofMillis(700));
        tokens = new PairingTokens(config.clientsFile());
        server = new TransportServer(config, tokens, listener);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    private TransportClient.Config clientConfig(String clientId, String token) {
        return TransportClient.Config.of(URI.create("wss://127.0.0.1:" + server.port()),
                        server.fingerprint(), clientId, "тестовый ноут", token)
                .withTimings(HEARTBEAT, Duration.ofMillis(100), Duration.ofMillis(400));
    }

    private static class EchoHandler implements TransportClient.Handler {
        final BlockingQueue<TransportClient.State> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Envelope> inbox = new LinkedBlockingQueue<>();

        @Override
        public Optional<Envelope> onRequest(Envelope request) {
            return switch (request.type()) {
                case RUN_SCRIPT_REQUEST -> {
                    RunScriptRequest r = request.payloadAs(RunScriptRequest.class);
                    yield Optional.of(request.reply(MessageType.RUN_SCRIPT_RESULT,
                            new RunScriptResult(0, "echo:" + r.code() + ":" + r.arguments().get("who"), "", 7, false)));
                }
                case CLIPBOARD_REQUEST -> Optional.of(request.reply(MessageType.CLIPBOARD_RESULT,
                        ClipboardResult.of("из буфера", false)));
                default -> Optional.empty();
            };
        }

        @Override
        public void onMessage(Envelope message) {
            inbox.add(message);
        }

        @Override
        public StatusPush status() {
            return new StatusPush("ноут", "Arch", "я", "wayland-0", List.of("scripts", "clipboard"), "test", 1);
        }

        @Override
        public void onStateChanged(TransportClient.State state) {
            states.add(state);
        }
    }

    private TransportClient connectedClient(EchoHandler handler) throws InterruptedException {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        client = new TransportClient(clientConfig(pairing.clientId(), pairing.token()), handler);
        client.start();
        assertTrue(client.awaitConnected(WAIT), "client did not connect: " + client.state());
        assertEquals(pairing.clientId(), connected.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        return client;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void echoRoundTrip_serverAsksClientAnswersByReplyTo() throws Exception {
        EchoHandler handler = new EchoHandler();
        String clientId = connectedClient(handler).state() == TransportClient.State.CONNECTED
                ? server.clients().getFirst().clientId() : null;
        assertNotNull(clientId);

        Envelope request = Envelope.of(MessageType.RUN_SCRIPT_REQUEST,
                new RunScriptRequest("print('hi')", Map.of("who", "мир"), 10));
        Envelope reply = await(server.request(clientId, request, WAIT));

        assertEquals(MessageType.RUN_SCRIPT_RESULT, reply.type());
        assertEquals(request.id(), reply.replyTo());
        RunScriptResult result = reply.payloadAs(RunScriptResult.class);
        assertEquals("echo:print('hi'):мир", result.stdout());
        assertTrue(result.isSuccess());

        Envelope clip = await(server.request(clientId, Envelope.of(MessageType.CLIPBOARD_REQUEST,
                ClipboardRequest.standard()), WAIT));
        assertEquals("из буфера", clip.payloadAs(ClipboardResult.class).text());
    }

    @Test
    void requestTraceIdReturnsWithReply() throws Exception {
        connectedClient(new EchoHandler());
        String clientId = server.clients().getFirst().clientId();

        Envelope request;
        try (var ignored = com.bebebe.agent.logging.TraceContext.open("trace-echo-1")) {
            request = Envelope.of(MessageType.CLIPBOARD_REQUEST, ClipboardRequest.standard());
        }
        assertEquals("trace-echo-1", request.traceId());

        Envelope reply = await(server.request(clientId, request, WAIT));
        assertEquals("trace-echo-1", reply.traceId(), "the client returned the same trace_id -- the request history is not broken");
    }

    @Test
    void clientSendsStatusOnConnectAndVoiceReachesListener() throws Exception {
        connectedClient(new EchoHandler());

        Envelope status = serverInbox.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(status);
        assertEquals(MessageType.STATUS_PUSH, status.type());
        assertEquals(List.of("scripts", "clipboard"), status.payloadAs(StatusPush.class).capabilities());
        assertEquals("ноут", server.clients().getFirst().status().hostname());

        client.send(Envelope.of(MessageType.VOICE_AUDIO_PUSH, VoiceAudioPush.wav(new byte[] {1, 2, 3}, 300)));
        Envelope voice = serverInbox.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(voice);
        assertEquals(MessageType.VOICE_AUDIO_PUSH, voice.type());
        assertEquals(3, voice.payloadAs(VoiceAudioPush.class).audio().length);
    }

    @Test
    void clientNotSupportingRequestAnswersError() throws Exception {
        connectedClient(new EchoHandler() {
            @Override
            public Optional<Envelope> onRequest(Envelope request) {
                return Optional.empty();
            }
        });
        String clientId = server.clients().getFirst().clientId();

        Envelope reply = await(server.request(clientId, Envelope.of(MessageType.CLIPBOARD_REQUEST,
                ClipboardRequest.standard()), WAIT));

        assertEquals(MessageType.ERROR, reply.type());
        assertTrue(reply.payload().path("code").asText().equals("unsupported"));
    }

    @Test
    void unansweredRequestTimesOutInsteadOfHangingForever() throws Exception {
        connectedClient(new EchoHandler() {
            @Override
            public Optional<Envelope> onRequest(Envelope request) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        });
        String clientId = server.clients().getFirst().clientId();

        CompletableFuture<Envelope> future = server.request(clientId,
                Envelope.of(MessageType.CLIPBOARD_REQUEST, ClipboardRequest.standard()), Duration.ofMillis(300));

        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertTrue(e.getCause() instanceof TimeoutException, String.valueOf(e.getCause()));
    }

    @Test
    void foreignTokenIsRejectedAndClientDoesNotReconnect() throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        EchoHandler handler = new EchoHandler();
        client = new TransportClient(clientConfig(pairing.clientId(), "wrong token"), handler);
        client.start();

        assertFalse(client.awaitConnected(WAIT));
        assertEquals(TransportClient.State.REJECTED, client.state());
        assertTrue(client.rejectionReason().contains("token"), client.rejectionReason());
        Thread.sleep(600);
        assertEquals(1, client.attempts(), "no more attempts after rejection");
        assertTrue(server.clients().isEmpty());
        assertTrue(connected.isEmpty());
    }

    @Test
    void revokedClientNoLongerPasses() throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        tokens.revoke(pairing.clientId());
        client = new TransportClient(clientConfig(pairing.clientId(), pairing.token()), new EchoHandler());
        client.start();

        assertFalse(client.awaitConnected(WAIT));
        assertEquals(TransportClient.State.REJECTED, client.state());
    }

    @Test
    void foreignFingerprintDoesNotConnect() throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        String wrong = "00:" + server.fingerprint().substring(3);
        TransportClient.Config cfg = clientConfig(pairing.clientId(), pairing.token());
        client = new TransportClient(new TransportClient.Config(cfg.server(), wrong, cfg.clientId(), cfg.clientName(),
                cfg.token(), cfg.heartbeat(), cfg.initialBackoff(), cfg.maxBackoff(), Duration.ofMillis(800),
                cfg.statusInterval()), new EchoHandler());
        client.start();

        assertFalse(client.awaitConnected(Duration.ofSeconds(2)));
        assertTrue(client.state() == TransportClient.State.RECONNECTING
                || client.state() == TransportClient.State.CONNECTING, String.valueOf(client.state()));
        assertTrue(server.clients().isEmpty(), "TLS did not match -- AUTH was never sent");
        assertTrue(connected.isEmpty());
    }

    @Test
    void withoutAuthConnectionClosesOnTimeout() throws Exception {
        RawClient raw = new RawClient(server);
        assertTrue(raw.connectBlocking(3, TimeUnit.SECONDS));

        assertTrue(raw.closed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the server must close the silent one");
        assertEquals(Protocol.CLOSE_AUTH_TIMEOUT, raw.closeCode.get());
        assertTrue(server.clients().isEmpty());
    }

    @Test
    void firstMessageMustBeAuth() throws Exception {
        RawClient raw = new RawClient(server);
        assertTrue(raw.connectBlocking(3, TimeUnit.SECONDS));

        raw.send(Codec.encode(Envelope.of(MessageType.PING)));

        assertTrue(raw.closed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(Protocol.CLOSE_AUTH_TIMEOUT, raw.closeCode.get());
    }

    @Test
    void clientSilentAfterAuthIsDisconnectedByServer() throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("тихий");
        RawClient raw = new RawClient(server);
        assertTrue(raw.connectBlocking(3, TimeUnit.SECONDS));
        raw.send(Codec.encode(Envelope.of(MessageType.AUTH,
                new AuthRequest(pairing.clientId(), "тихий", pairing.token(), Protocol.VERSION))));
        assertEquals(pairing.clientId(), connected.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));

        assertTrue(raw.closed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(Protocol.CLOSE_HEARTBEAT_TIMEOUT, raw.closeCode.get());
        assertEquals(pairing.clientId() + ":" + Protocol.CLOSE_HEARTBEAT_TIMEOUT,
                disconnected.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertTrue(raw.received.stream().anyMatch(m -> m.contains("\"PING\"")), "the server sent PING");
    }

    @Test
    void liveClientSurvivesSeveralHeartbeatIntervals() throws Exception {
        connectedClient(new EchoHandler());
        String clientId = server.clients().getFirst().clientId();

        Thread.sleep(HEARTBEAT.multipliedBy(5).toMillis());

        assertTrue(server.isConnected(clientId), "PING/PONG keep the connection");
        assertEquals(TransportClient.State.CONNECTED, client.state());
        assertTrue(disconnected.isEmpty());
    }

    @Test
    void reconnectOfSameClientReplacesOld() throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        EchoHandler first = new EchoHandler();
        client = new TransportClient(clientConfig(pairing.clientId(), pairing.token()), first);
        client.start();
        assertTrue(client.awaitConnected(WAIT));
        connected.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        TransportClient second = new TransportClient(clientConfig(pairing.clientId(), pairing.token()), new EchoHandler());
        try {
            second.start();
            assertTrue(second.awaitConnected(WAIT));
            assertEquals(pairing.clientId(), connected.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(1, server.clients().size(), "one client -- one connection");

            assertTrue(first.states.stream().anyMatch(s -> s == TransportClient.State.RECONNECTING)
                    || client.state() == TransportClient.State.RECONNECTING
                    || client.state() == TransportClient.State.CONNECTED);
        } finally {
            second.close();
        }
    }

    @Test
    void afterServerRestartClientReturnsByItself() throws Exception {
        EchoHandler handler = new EchoHandler();
        connectedClient(handler);
        String clientId = server.clients().getFirst().clientId();

        server.stop();
        assertTrue(waitFor(() -> client.state() == TransportClient.State.RECONNECTING, WAIT),
                "after the server stops the client must go to RECONNECTING, not " + client.state());
        int attemptsWhileDown = client.attempts();
        Thread.sleep(700);
        assertTrue(client.attempts() > attemptsWhileDown, "the client keeps trying");

        server = new TransportServer(config, tokens, listener);
        server.start();

        assertTrue(waitFor(() -> client.state() == TransportClient.State.CONNECTED, WAIT), "the client did not return");
        assertTrue(waitFor(() -> server.isConnected(clientId), WAIT));
        Envelope reply = await(server.request(clientId,
                Envelope.of(MessageType.CLIPBOARD_REQUEST, ClipboardRequest.standard()), WAIT));
        assertEquals(MessageType.CLIPBOARD_RESULT, reply.type(), "requests flow again after the return");
        assertTrue(handler.states.contains(TransportClient.State.RECONNECTING));
    }

    @Test
    void pauseBetweenAttemptsGrows() throws Exception {
        PairingTokens.Pairing pairing = tokens.issue("ноут");
        server.stop();
        EchoHandler handler = new EchoHandler();
        client = new TransportClient(clientConfig(pairing.clientId(), pairing.token()), handler);
        long started = System.nanoTime();
        client.start();

        Thread.sleep(1200);
        int attempts = client.attempts();
        assertTrue(attempts >= 2 && attempts <= 5, "attempts in 1.2 s: " + attempts);
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 1200);
    }

    @Test
    void sendingToDisconnectedClientIsImmediateError() {
        assertThrows(IllegalStateException.class, () -> server.send("nobody", Envelope.of(MessageType.PING)));
        CompletableFuture<Envelope> f = server.request("nobody", Envelope.of(MessageType.PING), WAIT);
        assertTrue(f.isCompletedExceptionally());
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static final class RawClient extends WebSocketClient {
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Integer> closeCode = new AtomicReference<>(0);
        final List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();

        RawClient(TransportServer server) {
            super(URI.create("wss://127.0.0.1:" + server.port()));
            setConnectionLostTimeout(0);
            setSocketFactory(PinnedTrust.sslContextFor(server.fingerprint()).getSocketFactory());
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
            received.add(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closeCode.set(code);
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}
