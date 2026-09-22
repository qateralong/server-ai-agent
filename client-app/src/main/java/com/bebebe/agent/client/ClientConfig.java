package com.bebebe.agent.client;

import com.bebebe.agent.capture.HotkeyConfig;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.ConfigSection;
import com.bebebe.agent.i18n.Language;
import com.bebebe.agent.script.runtime.ScriptConfig;
import com.bebebe.agent.transport.TransportClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record ClientConfig(
        Path path,
        URI serverUrl,
        String fingerprint,
        String clientId,
        String token,
        String name,
        boolean tray,
        Language language,
        HotkeyConfig hotkey,
        String audioDevice,
        Duration maxRecording,
        ScriptConfig scripts,
        Path logDir,
        String logLevel
) {

    public static final String PROPERTY = "bebebe.client.config";
    public static final String ENV = "BEBEBE_CLIENT_CONFIG";

    public static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String prop = System.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) {
            out.add(Path.of(prop));
        }
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            out.add(Path.of(env));
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        out.add(base.resolve("bebebe-agent").resolve("client.toml"));
        out.add(Path.of("config", "client.toml"));
        return out;
    }

    public static Optional<Path> locate() {
        return candidates().stream().filter(Files::isReadable).findFirst();
    }

    public static ClientConfig load(Path file) {
        return from(AppConfig.load(file));
    }

    public static ClientConfig from(AppConfig config) {
        ConfigSection server = config.section("server");
        ConfigSection client = config.section("client");
        ConfigSection hotkey = config.section("hotkey");
        ConfigSection audio = config.section("audio");
        ConfigSection logging = config.section("logging");
        String url = server.requiredString("url");
        URI uri = URI.create(url);
        if (!"wss".equals(uri.getScheme())) {
            throw new IllegalArgumentException("server.url must start with wss:// -- the client does not connect without TLS: " + url);
        }
        return new ClientConfig(
                config.path(),
                uri,
                server.requiredString("fingerprint"),
                server.requiredString("client_id"),
                server.requiredString("token"),
                client.string("name", hostname()),
                client.bool("tray", true),
                Language.from(client.string("language", "auto")),
                new HotkeyConfig(expand(hotkey.string("helper", "native/evdev-hotkey/build/evdev-hotkey")),
                        hotkey.string("key", "KEY_HOME"), hotkey.string("device", "")),
                audio.string("device", ""),
                audio.seconds("max_seconds", Duration.ofSeconds(120)),
                ScriptConfig.from(config.section(ScriptConfig.SECTION)),
                expand(logging.string("dir", "~/.local/share/bebebe-client/logs")),
                logging.string("level", "INFO"));
    }

    public TransportClient.Config transport() {
        return TransportClient.Config.of(serverUrl, fingerprint, clientId, name, token);
    }

    static Path expand(String raw) {
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), raw.substring(2));
        }
        return Path.of(raw);
    }

    private static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "client";
        }
    }

    @Override
    public String toString() {
        return "ClientConfig[%s -> %s as «%s» (%s), token %s, hotkey %s]".formatted(
                path, serverUrl, name, clientId, token.isBlank() ? "<empty>" : "<set>", hotkey.key());
    }
}
