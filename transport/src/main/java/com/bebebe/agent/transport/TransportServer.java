package com.bebebe.agent.transport;

import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.transport.messages.AuthRequest;
import com.bebebe.agent.transport.messages.AuthResult;
import com.bebebe.agent.transport.messages.ErrorPayload;
import com.bebebe.agent.transport.messages.StatusPush;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TransportServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TransportServer.class);

    public interface Listener {

        default void onClientConnected(ClientInfo client) {
        }

        default void onClientDisconnected(ClientInfo client, int code, String reason) {
        }

        default void onMessage(ClientInfo client, Envelope message) {
        }
    }

    public record ClientInfo(String clientId, String name, String remoteAddress,
                             Instant connectedAt, StatusPush status) {
    }

    private static final class Session {
        final String remote;
        final String traceId = TraceContext.newId();
        final Instant openedAt = Instant.now();
        volatile String clientId;
        volatile String name = "";
        volatile boolean authenticated;
        volatile long lastSeenNanos = System.nanoTime();
        volatile StatusPush status;
        volatile ScheduledFuture<?> authTimer;

        Session(String remote) {
            this.remote = remote;
        }

        ClientInfo info() {
            return new ClientInfo(clientId, name, remote, openedAt, status);
        }
    }

    private final TransportConfig config;
    private final PairingTokens tokens;
    private final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ServerCertificate certificate;
    private final Map<String, WebSocket> byClientId = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Envelope>> pending = new ConcurrentHashMap<>();

    private final Map<String, String> pendingClient = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "transport-server-timer");
        t.setDaemon(true);
        return t;
    });
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile Exception startFailure;
    private volatile Inner inner;

    public TransportServer(TransportConfig config, PairingTokens tokens, Listener listener) {
        this.config = config;
        this.tokens = tokens;
        if (listener != null) {
            listeners.add(listener);
        }
        this.certificate = ServerCertificate.ensure(config.keystore());
    }

    public TransportServer(TransportConfig config, PairingTokens tokens) {
        this(config, tokens, null);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public TransportConfig config() {
        return config;
    }

    public synchronized void start() {
        if (inner != null) {
            return;
        }
        Inner server = new Inner(new InetSocketAddress(config.bind(), config.port()));
        server.setReuseAddr(true);
        server.setConnectionLostTimeout(0);
        server.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(certificate.sslContext()));
        server.setDaemon(true);
        inner = server;
        server.start();
        try {
            if (!started.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Transport did not start within 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the transport", e);
        }
        if (startFailure != null) {
            throw new IllegalStateException("Transport failed to start: " + startFailure.getMessage(), startFailure);
        }
        scheduler.scheduleAtFixedRate(this::heartbeatTick,
                config.heartbeat().toMillis(), config.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
        log.atInfo()
                .addKeyValue("event", "transport.listen")
                .addKeyValue("port", port())
                .addKeyValue("fingerprint", fingerprint())
                .log("Transport listening on wss://{}:{} (fingerprint {})", config.bind(), port(), fingerprint());
    }

    public synchronized void stop() {
        Inner server = inner;
        if (server == null) {
            return;
        }
        inner = null;
        for (WebSocket conn : server.getConnections()) {
            conn.close(Protocol.CLOSE_SERVER_SHUTDOWN, "server is shutting down");
        }
        try {
            server.stop(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Transport stopped")));
        pending.clear();
        byClientId.clear();
        log.info("Transport stopped");
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }

    public int port() {
        Inner server = inner;
        return server == null ? config.port() : server.getPort();
    }

    public String fingerprint() {
        return certificate.fingerprint();
    }

    public List<ClientInfo> clients() {
        List<ClientInfo> out = new ArrayList<>();
        for (WebSocket conn : byClientId.values()) {
            Session s = conn.getAttachment();
            if (s != null && s.authenticated) {
                out.add(s.info());
            }
        }
        return out;
    }

    public boolean isConnected(String clientId) {
        WebSocket conn = byClientId.get(clientId);
        return conn != null && conn.isOpen();
    }

    public boolean disconnect(String clientId, String reason) {
        WebSocket conn = byClientId.get(clientId);
        if (conn == null) {
            return false;
        }
        conn.close(Protocol.CLOSE_KICKED, reason);
        return true;
    }

    public void send(String clientId, Envelope envelope) {
        WebSocket conn = byClientId.get(clientId);
        if (conn == null || !conn.isOpen()) {
            throw new IllegalStateException("Client " + clientId + " is not connected");
        }
        conn.send(Codec.encode(envelope));
    }

    public CompletableFuture<Envelope> request(String clientId, Envelope envelope, Duration timeout) {
        CompletableFuture<Envelope> future = new CompletableFuture<>();
        pending.put(envelope.id(), future);
        pendingClient.put(envelope.id(), clientId);
        future.whenComplete((r, e) -> pendingClient.remove(envelope.id()));
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (pending.remove(envelope.id()) != null) {
                future.completeExceptionally(new TimeoutException(
                        "Client " + clientId + " did not answer " + envelope + " within " + timeout.toSeconds() + " s"));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((r, e) -> timer.cancel(false));
        try {
            send(clientId, envelope);
        } catch (RuntimeException e) {
            pending.remove(envelope.id());
            future.completeExceptionally(e);
        }
        return future;
    }

    private void handleOpen(WebSocket conn) {
        Session session = new Session(String.valueOf(conn.getRemoteSocketAddress()));
        conn.setAttachment(session);
        try (var ignored = TraceContext.open(session.traceId)) {
            log.atInfo().addKeyValue("event", "transport.connect").addKeyValue("remote", session.remote)
                    .log("Connection from {} -- waiting for AUTH", session.remote);
        }
        session.authTimer = scheduler.schedule(() -> {
            if (!session.authenticated && conn.isOpen()) {
                try (var ignored = TraceContext.open(session.traceId)) {
                    log.atWarn().addKeyValue("event", "transport.auth").addKeyValue("remote", session.remote)
                            .addKeyValue("accepted", false)
                            .log("AUTH from {} not received within {} s -- closing", session.remote,
                                    config.authTimeout().toSeconds());
                }
                conn.close(Protocol.CLOSE_AUTH_TIMEOUT, "waited for AUTH");
            }
        }, config.authTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleMessage(WebSocket conn, String text) {
        Session session = conn.getAttachment();
        if (session == null) {
            return;
        }
        session.lastSeenNanos = System.nanoTime();
        Envelope envelope;
        try {
            envelope = Codec.decode(text);
        } catch (ProtocolException e) {
            try (var ignored = TraceContext.open(session.traceId)) {
                log.warn("Protocol violation from {}: {}", session.remote, e.getMessage());
            }
            if (session.authenticated) {
                conn.send(Codec.encode(Envelope.of(MessageType.ERROR, new ErrorPayload("bad_message", e.getMessage()))));
            } else {
                conn.close(Protocol.CLOSE_AUTH_TIMEOUT, "expected AUTH per protocol");
            }
            return;
        }

        String traceId = envelope.traceId() != null ? envelope.traceId() : session.traceId;
        try (var ignored = TraceContext.open(traceId)) {
            if (!session.authenticated) {
                authenticate(conn, session, envelope);
                return;
            }
            dispatch(conn, session, envelope);
        }
    }

    private void authenticate(WebSocket conn, Session session, Envelope envelope) {
        if (envelope.type() != MessageType.AUTH) {
            log.atWarn().addKeyValue("event", "transport.auth").addKeyValue("accepted", false)
                    .log("First message from {} was {}, not AUTH", session.remote, envelope.type());
            conn.close(Protocol.CLOSE_AUTH_TIMEOUT, "AUTH must come first");
            return;
        }
        AuthRequest auth;
        try {
            auth = envelope.payloadAs(AuthRequest.class);
        } catch (ProtocolException e) {
            reject(conn, session, envelope, "AUTH cannot be parsed: " + e.getMessage());
            return;
        }
        if (auth.protocolVersion() != Protocol.VERSION) {
            reject(conn, session, envelope, "protocol version " + auth.protocolVersion()
                    + ", the server speaks " + Protocol.VERSION);
            return;
        }
        Optional<PairingTokens.PairedClient> paired = tokens.verify(auth.clientId(), auth.token());
        if (paired.isEmpty()) {
            reject(conn, session, envelope, "unknown client or wrong token");
            return;
        }

        session.clientId = auth.clientId();
        session.name = auth.clientName() == null || auth.clientName().isBlank()
                ? paired.get().name() : auth.clientName();
        session.authenticated = true;
        if (session.authTimer != null) {
            session.authTimer.cancel(false);
        }
        WebSocket previous = byClientId.put(session.clientId, conn);
        if (previous != null && previous != conn && previous.isOpen()) {
            log.info("Client {} reconnected -- closing the previous connection", session.clientId);
            previous.close(Protocol.CLOSE_REPLACED, "replaced by a new connection");
        }
        tokens.touch(session.clientId);
        conn.send(Codec.encode(envelope.reply(MessageType.AUTH_RESULT,
                AuthResult.ok(config.serverName(), (int) config.heartbeat().toSeconds()))));
        log.atInfo()
                .addKeyValue("event", "transport.auth")
                .addKeyValue("accepted", true)
                .addKeyValue("client_id", session.clientId)
                .addKeyValue("client_name", session.name)
                .addKeyValue("remote", session.remote)
                .log("Client «{}» ({}) connected from {}", session.name, session.clientId, session.remote);
        safely(l -> l.onClientConnected(session.info()));
    }

    private void reject(WebSocket conn, Session session, Envelope envelope, String reason) {
        log.atWarn()
                .addKeyValue("event", "transport.auth")
                .addKeyValue("accepted", false)
                .addKeyValue("remote", session.remote)
                .addKeyValue("reason", reason)
                .log("AUTH from {} rejected: {}", session.remote, reason);
        try {
            conn.send(Codec.encode(envelope.reply(MessageType.AUTH_RESULT, AuthResult.rejected(reason))));
        } finally {
            conn.close(Protocol.CLOSE_AUTH_REJECTED, reason);
        }
    }

    private void dispatch(WebSocket conn, Session session, Envelope envelope) {
        if (envelope.isReply()) {
            CompletableFuture<Envelope> waiting = pending.remove(envelope.replyTo());
            if (waiting != null) {
                waiting.complete(envelope);
                return;
            }
        }
        switch (envelope.type()) {
            case PING -> conn.send(Codec.encode(envelope.reply(MessageType.PONG)));
            case PONG -> {  }
            case STATUS_PUSH -> {
                try {
                    session.status = envelope.payloadAs(StatusPush.class);
                    log.atDebug().addKeyValue("event", "transport.status").addKeyValue("client_id", session.clientId)
                            .log("Client {} status: {}", session.clientId, session.status);
                } catch (ProtocolException e) {
                    conn.send(Codec.encode(envelope.reply(MessageType.ERROR, new ErrorPayload("bad_payload", e.getMessage()))));
                    return;
                }
                safely(l -> l.onMessage(session.info(), envelope));
            }
            case AUTH -> conn.send(Codec.encode(envelope.reply(MessageType.ERROR,
                    new ErrorPayload("unsupported", "already authenticated"))));
            case RUN_SCRIPT_REQUEST, CLIPBOARD_REQUEST -> conn.send(Codec.encode(envelope.reply(MessageType.ERROR,
                    new ErrorPayload("unsupported", envelope.type() + " is sent by the server, not the client"))));
            default -> safely(l -> l.onMessage(session.info(), envelope));
        }
    }

    private void handleClose(WebSocket conn, int code, String reason, boolean remote) {
        Session session = conn.getAttachment();
        if (session == null) {
            return;
        }
        if (session.authTimer != null) {
            session.authTimer.cancel(false);
        }
        boolean wasCurrent = session.clientId != null && byClientId.remove(session.clientId, conn);
        if (wasCurrent) {

            for (Map.Entry<String, String> e : List.copyOf(pendingClient.entrySet())) {
                if (e.getValue().equals(session.clientId)) {
                    CompletableFuture<Envelope> waiting = pending.remove(e.getKey());
                    if (waiting != null) {
                        waiting.completeExceptionally(new java.io.IOException(
                                "соединение с компьютером оборвалось (" + Protocol.describeClose(code) + ")"));
                    }
                }
            }
        }
        try (var ignored = TraceContext.open(session.traceId)) {
            log.atInfo()
                    .addKeyValue("event", "transport.disconnect")
                    .addKeyValue("client_id", session.clientId == null ? "" : session.clientId)
                    .addKeyValue("remote", session.remote)
                    .addKeyValue("code", code)
                    .addKeyValue("by_peer", remote)
                    .log("Connection {} closed: {} ({})",
                            session.clientId == null ? session.remote : session.clientId,
                            Protocol.describeClose(code), reason == null || reason.isBlank() ? "no reason" : reason);
        }
        if (session.authenticated && wasCurrent) {
            safely(l -> l.onClientDisconnected(session.info(), code, reason));
        }
    }

    private void heartbeatTick() {
        Inner server = inner;
        if (server == null) {
            return;
        }
        long limit = config.heartbeat().multipliedBy(2).toNanos();
        long now = System.nanoTime();
        for (WebSocket conn : server.getConnections()) {
            Session session = conn.getAttachment();
            if (session == null || !session.authenticated || !conn.isOpen()) {
                continue;
            }
            if (now - session.lastSeenNanos > limit) {
                try (var ignored = TraceContext.open(session.traceId)) {
                    log.atWarn().addKeyValue("event", "transport.heartbeat.timeout")
                            .addKeyValue("client_id", session.clientId)
                            .log("Client {} silent for more than {} s -- closing", session.clientId,
                                    config.heartbeat().multipliedBy(2).toSeconds());
                }
                conn.close(Protocol.CLOSE_HEARTBEAT_TIMEOUT, "no answer to PING");
                continue;
            }
            try {
                conn.send(Codec.encode(Envelope.of(MessageType.PING)));
            } catch (RuntimeException e) {
                log.debug("PING to client {} not sent: {}", session.clientId, e.getMessage());
            }
        }
    }

    private void safely(java.util.function.Consumer<Listener> action) {
        for (Listener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.error("Transport listener failed", e);
            }
        }
    }

    private final class Inner extends WebSocketServer {

        Inner(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onStart() {
            started.countDown();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            handleOpen(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            handleMessage(conn, message);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            handleClose(conn, code, reason, remote);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn == null) {

                startFailure = ex;
                started.countDown();
                log.error("Transport error: {}", ex.getMessage());
                return;
            }
            Session session = conn.getAttachment();
            String who = session == null ? "?" : (session.clientId == null ? session.remote : session.clientId);

            log.debug("Connection error {}: {}", who, ex.toString());
        }
    }
}
