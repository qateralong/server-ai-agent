package com.bebebe.agent.transport;

import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.transport.messages.AuthRequest;
import com.bebebe.agent.transport.messages.AuthResult;
import com.bebebe.agent.transport.messages.ErrorPayload;
import com.bebebe.agent.transport.messages.StatusPush;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class TransportClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TransportClient.class);

    public record Config(URI server, String serverFingerprint, String clientId, String clientName, String token,
                         Duration heartbeat, Duration initialBackoff, Duration maxBackoff,
                         Duration connectTimeout, Duration statusInterval) {

        public static Config of(URI server, String fingerprint, String clientId, String clientName, String token) {
            return new Config(server, fingerprint, clientId, clientName, token,
                    Duration.ofSeconds(15), Duration.ofSeconds(1), Duration.ofSeconds(60),
                    Duration.ofSeconds(10), Duration.ofSeconds(60));
        }

        public Config withTimings(Duration heartbeat, Duration initialBackoff, Duration maxBackoff) {
            return new Config(server, serverFingerprint, clientId, clientName, token,
                    heartbeat, initialBackoff, maxBackoff, connectTimeout, statusInterval);
        }

        @Override
        public String toString() {
            return "Config[%s as «%s» (%s), token %s]".formatted(server, clientName, clientId,
                    token == null || token.isBlank() ? "<empty>" : "<set>");
        }
    }

    public interface Handler {

        default Optional<Envelope> onRequest(Envelope request) {
            return Optional.empty();
        }

        default void onMessage(Envelope message) {
        }

        default StatusPush status() {
            return StatusPush.local(List.of(), "", Instant.now());
        }

        default void onStateChanged(State state) {
        }
    }

    public enum State { NEW, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, REJECTED, STOPPED }

    private final Config config;
    private final Handler handler;
    private final Backoff backoff;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transport-client-work");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile Thread loop;
    private volatile Connection current;
    private volatile String rejectionReason = "";
    private volatile int attempts;

    public TransportClient(Config config, Handler handler) {
        this.config = config;
        this.handler = handler == null ? new Handler() { } : handler;
        this.backoff = new Backoff(config.initialBackoff(), config.maxBackoff());
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::runLoop, "transport-client");
        t.setDaemon(true);
        loop = t;
        t.start();
    }

    @Override
    public void close() {
        running = false;
        Connection c = current;
        if (c != null) {
            c.close(1000, "client is stopping");
        }
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
        work.shutdownNow();
        setState(State.STOPPED);
    }

    public State state() {
        return state.get();
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public int attempts() {
        return attempts;
    }

    public boolean awaitConnected(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (state.get() == State.CONNECTED) {
                return true;
            }
            if (state.get() == State.REJECTED || state.get() == State.STOPPED) {
                return false;
            }
            Thread.sleep(20);
        }
        return state.get() == State.CONNECTED;
    }

    public void send(Envelope envelope) {
        Connection c = current;
        if (c == null || state.get() != State.CONNECTED) {
            throw new IllegalStateException("Client is not connected: " + state.get());
        }
        c.sendEnvelope(envelope);
    }

    private void runLoop() {
        while (running) {
            attempts++;
            Connection connection = new Connection(TraceContext.newId());
            current = connection;
            boolean authenticated = false;
            try (var ignored = TraceContext.open(connection.traceId)) {
                setState(attempts == 1 ? State.CONNECTING : State.RECONNECTING);
                authenticated = connection.connectAndAuthenticate();
                if (authenticated) {
                    backoff.reset();
                    connection.serve();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                try (var ignored = TraceContext.open(connection.traceId)) {
                    log.warn("Connection to {} failed: {}", config.server(), e.toString());
                }
            } finally {
                connection.close(1000, "");
            }
            if (!running) {
                break;
            }
            if (state.get() == State.REJECTED) {
                running = false;
                break;
            }
            Duration pause = backoff.next();
            setState(State.RECONNECTING);
            try (var ignored = TraceContext.open(connection.traceId)) {
                log.atInfo().addKeyValue("event", "transport.reconnect")
                        .addKeyValue("attempt", attempts).addKeyValue("pause_ms", pause.toMillis())
                        .log("Will reconnect to {} in {} ms (attempt {})", config.server(), pause.toMillis(), attempts + 1);
            }
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        current = null;
        if (state.get() != State.REJECTED) {
            setState(State.STOPPED);
        }
    }

    private void setState(State next) {
        State previous = state.getAndSet(next);
        if (previous != next) {
            try {
                handler.onStateChanged(next);
            } catch (RuntimeException e) {
                log.error("State handler failed", e);
            }
        }
    }

    private final class Connection {
        final String traceId;
        final Instant openedAt = Instant.now();
        final CountDownLatch authDone = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile AuthResult authResult;
        volatile long lastSeenNanos = System.nanoTime();
        volatile long lastSentNanos = System.nanoTime();
        volatile int closeCode;
        volatile String closeReason = "";
        final WebSocketClient socket;

        Connection(String traceId) {
            this.traceId = traceId;
            this.socket = new WebSocketClient(config.server()) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    try (var ignored = TraceContext.open(traceId)) {
                        log.atInfo().addKeyValue("event", "transport.connect").addKeyValue("server", config.server().toString())
                                .log("Connection to {} opened, sending AUTH", config.server());
                        setState(State.AUTHENTICATING);
                        sendEnvelope(Envelope.of(MessageType.AUTH, new AuthRequest(
                                config.clientId(), config.clientName(), config.token(), Protocol.VERSION)));
                    }
                }

                @Override
                public void onMessage(String text) {
                    lastSeenNanos = System.nanoTime();
                    handleMessage(text);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    closeCode = code;
                    closeReason = reason == null ? "" : reason;
                    try (var ignored = TraceContext.open(traceId)) {
                        log.atInfo().addKeyValue("event", "transport.disconnect").addKeyValue("code", code)
                                .addKeyValue("by_peer", remote)
                                .log("Connection to {} closed: {} ({})", config.server(),
                                        Protocol.describeClose(code), closeReason.isBlank() ? "no reason" : closeReason);
                    }
                    authDone.countDown();
                    closed.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    try (var ignored = TraceContext.open(traceId)) {
                        log.warn("Connection error with {}: {}", config.server(), ex.toString());
                    }
                }
            };
            socket.setConnectionLostTimeout(0);
            socket.setSocketFactory(PinnedTrust.sslContextFor(config.serverFingerprint()).getSocketFactory());
        }

        boolean connectAndAuthenticate() throws InterruptedException {
            boolean opened = socket.connectBlocking(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!opened) {
                log.warn("Cannot connect to {} within {} s", config.server(), config.connectTimeout().toSeconds());
                return false;
            }
            if (!authDone.await(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Server {} did not answer AUTH within {} s", config.server(), config.connectTimeout().toSeconds());
                return false;
            }
            AuthResult result = authResult;
            if (result == null) {

                if (closeCode == Protocol.CLOSE_AUTH_REJECTED) {
                    rejected("server closed the connection with code 4401");
                }
                return false;
            }
            if (!result.accepted()) {
                rejected(result.reason());
                return false;
            }
            log.atInfo().addKeyValue("event", "transport.auth").addKeyValue("accepted", true)
                    .addKeyValue("server_name", result.serverName())
                    .log("Server «{}» accepted client «{}»", result.serverName(), config.clientName());
            setState(State.CONNECTED);
            sendStatus();
            return true;
        }

        private void rejected(String reason) {
            rejectionReason = reason == null ? "" : reason;
            log.atError().addKeyValue("event", "transport.auth").addKeyValue("accepted", false)
                    .addKeyValue("reason", rejectionReason)
                    .log("Server {} rejected the client: {}. Not reconnecting -- a new pairing is needed.",
                            config.server(), rejectionReason);
            setState(State.REJECTED);
        }

        void serve() throws InterruptedException {
            long heartbeatNanos = config.heartbeat().toNanos();
            long statusNanos = config.statusInterval().toNanos();
            long lastStatus = System.nanoTime();
            long tick = Math.max(50, Math.min(1000, config.heartbeat().toMillis() / 4));
            while (running && !closed.await(tick, TimeUnit.MILLISECONDS)) {
                long now = System.nanoTime();
                if (now - lastSeenNanos > 2 * heartbeatNanos) {
                    log.atWarn().addKeyValue("event", "transport.heartbeat.timeout")
                            .log("Server {} silent for more than {} s -- considering the connection dead",
                                    config.server(), config.heartbeat().multipliedBy(2).toSeconds());
                    close(Protocol.CLOSE_HEARTBEAT_TIMEOUT, "no PING from server");
                    break;
                }
                if (now - lastSentNanos > heartbeatNanos) {
                    sendQuietly(Envelope.of(MessageType.PING));
                }
                if (now - lastStatus > statusNanos) {
                    lastStatus = now;
                    sendStatus();
                }
            }
        }

        private void handleMessage(String text) {
            Envelope envelope;
            try {
                envelope = Codec.decode(text);
            } catch (ProtocolException e) {
                try (var ignored = TraceContext.open(traceId)) {
                    log.warn("Server message violates the protocol: {}", e.getMessage());
                }
                return;
            }
            String messageTrace = envelope.traceId() != null ? envelope.traceId() : traceId;
            try (var ignored = TraceContext.open(messageTrace)) {
                switch (envelope.type()) {
                    case AUTH_RESULT -> {
                        try {
                            authResult = envelope.payloadAs(AuthResult.class);
                        } catch (ProtocolException e) {
                            log.warn("AUTH_RESULT cannot be parsed: {}", e.getMessage());
                        }
                        authDone.countDown();
                    }
                    case PING -> sendQuietly(envelope.reply(MessageType.PONG));
                    case PONG -> {  }
                    case RUN_SCRIPT_REQUEST, CLIPBOARD_REQUEST -> work.submit(TraceContext.wrap(messageTrace, () -> {
                        Envelope reply;
                        try {
                            reply = handler.onRequest(envelope).orElseGet(() -> envelope.reply(MessageType.ERROR,
                                    new ErrorPayload("unsupported", "client does not support " + envelope.type())));
                        } catch (RuntimeException e) {
                            log.error("Handler {} failed", envelope.type(), e);
                            reply = envelope.reply(MessageType.ERROR, new ErrorPayload("internal", String.valueOf(e.getMessage())));
                        }
                        sendQuietly(reply);
                    }));
                    default -> {
                        try {
                            handler.onMessage(envelope);
                        } catch (RuntimeException e) {
                            log.error("Message handler {} failed", envelope.type(), e);
                        }
                    }
                }
            }
        }

        void sendStatus() {
            try {
                sendQuietly(Envelope.of(MessageType.STATUS_PUSH, handler.status()));
            } catch (RuntimeException e) {
                log.warn("STATUS_PUSH could not be built: {}", e.getMessage());
            }
        }

        void sendEnvelope(Envelope envelope) {
            socket.send(Codec.encode(envelope));
            lastSentNanos = System.nanoTime();
        }

        void sendQuietly(Envelope envelope) {
            try {
                sendEnvelope(envelope);
            } catch (RuntimeException e) {
                log.debug("{} not sent: {}", envelope, e.getMessage());
            }
        }

        void close(int code, String reason) {
            try {
                if (socket.isOpen()) {
                    socket.close(code, reason);
                }
            } catch (RuntimeException ignored) {

            }
        }
    }
}
