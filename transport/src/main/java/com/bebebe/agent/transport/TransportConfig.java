package com.bebebe.agent.transport;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;
import java.time.Duration;

public record TransportConfig(
        boolean enabled,
        String bind,
        int port,
        Path dataDir,
        String serverName,
        Duration heartbeat,
        Duration authTimeout
) {

    public static final String SECTION = "transport";
    public static final int DEFAULT_PORT = 8765;

    public TransportConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("transport.port out of range: " + port);
        }
        if (heartbeat.isZero() || heartbeat.isNegative()) {
            throw new IllegalArgumentException("transport.heartbeat_seconds must be > 0");
        }
        if (authTimeout.isZero() || authTimeout.isNegative()) {
            throw new IllegalArgumentException("transport.auth_timeout_seconds must be > 0");
        }
    }

    public static TransportConfig from(ConfigSection section) {
        return new TransportConfig(
                section.bool("enabled", false),
                section.string("bind", "0.0.0.0"),
                section.integer("port", DEFAULT_PORT),
                expand(section.string("data_dir", "~/.local/share/bebebe-agent/transport")),
                section.string("server_name", "bebebe"),
                section.seconds("heartbeat_seconds", Duration.ofSeconds(15)),
                section.seconds("auth_timeout_seconds", Duration.ofSeconds(5)));
    }

    public static TransportConfig inDirectory(Path dataDir, int port, Duration heartbeat, Duration authTimeout) {
        return new TransportConfig(true, "127.0.0.1", port, dataDir, "test", heartbeat, authTimeout);
    }

    public Path keystore() {
        return dataDir.resolve("server.p12");
    }

    public Path clientsFile() {
        return dataDir.resolve("clients.json");
    }

    private static Path expand(String raw) {
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), raw.substring(2));
        }
        return Path.of(raw);
    }

    @Override
    public String toString() {
        return "TransportConfig[%s:%d, heartbeat=%ds, auth=%ds, data=%s]".formatted(
                bind, port, heartbeat.toSeconds(), authTimeout.toSeconds(), dataDir);
    }
}
