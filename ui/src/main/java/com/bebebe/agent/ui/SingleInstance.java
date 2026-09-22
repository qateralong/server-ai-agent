package com.bebebe.agent.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class SingleInstance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstance.class);
    private static final String COMMAND_SHOW = "show";

    private final Path socket;
    private ServerSocketChannel server;
    private volatile Consumer<String> handler = cmd -> { };

    public SingleInstance() {
        this(defaultSocketPath());
    }

    public SingleInstance(Path socket) {
        this.socket = socket;
    }

    static Path defaultSocketPath() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path dir = runtime == null || runtime.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(runtime);
        return dir.resolve("bebebe-agent.sock");
    }

    public boolean tryBecomePrimary() {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socket);
        if (Files.exists(socket)) {
            if (send(address, COMMAND_SHOW)) {
                log.info("The agent is already running -- asked it to show the window");
                return false;
            }
            try {
                Files.deleteIfExists(socket);
            } catch (IOException e) {
                log.warn("Cannot delete stale socket {}: {}", socket, e.getMessage());
            }
        }
        try {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(address);
        } catch (IOException e) {
            log.warn("Cannot bind the single-instance socket {}: {} -- running without it", socket, e.getMessage());
            server = null;
            return true;
        }
        Thread listener = new Thread(this::listen, "single-instance");
        listener.setDaemon(true);
        listener.start();
        return true;
    }

    public void onCommand(Consumer<String> handler) {
        this.handler = handler;
    }

    private void listen() {
        while (server != null && server.isOpen()) {
            try (SocketChannel client = server.accept()) {
                ByteBuffer buffer = ByteBuffer.allocate(64);
                int read = client.read(buffer);
                String command = read <= 0 ? "" : new String(buffer.array(), 0, read, StandardCharsets.UTF_8).strip();
                if (!command.isEmpty()) {
                    log.info("Command from another instance: {}", command);
                    handler.accept(command);
                }
            } catch (IOException e) {
                if (server != null && server.isOpen()) {
                    log.debug("Single-instance socket: {}", e.getMessage());
                }
            }
        }
    }

    private static boolean send(UnixDomainSocketAddress address, String command) {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            channel.write(ByteBuffer.wrap((command + "\n").getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            if (server != null) {
                server.close();
            }
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {

        }
    }
}
