package com.bebebe.agent.server;

import com.bebebe.agent.assembly.AgentAssembly;
import com.bebebe.agent.assembly.Wiring;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.ollama.OllamaStats;
import com.bebebe.agent.stt.RemoteVoiceIngest;
import com.bebebe.agent.stt.SttConfig;
import com.bebebe.agent.telegram.menu.ServerStatus;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.remote.RemoteActionExecutor;
import com.bebebe.agent.transport.remote.RemoteClipboardTool;
import com.bebebe.agent.watchdog.UpdateChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ServerRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerRuntime.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Instant startedAt = Instant.now();
    private final AppConfig config;
    private final TransportServer transport;
    private final AgentAssembly app;
    private final RemoteVoiceIngest voice;

    private ServerRuntime(AppConfig config, TransportServer transport, AgentAssembly app, RemoteVoiceIngest voice) {
        this.config = config;
        this.transport = transport;
        this.app = app;
        this.voice = voice;
    }

    static ServerRuntime start(AppConfig config, AppSettings settings) {
        return start(config, settings, null, null);
    }

    static ServerRuntime start(AppConfig config, AppSettings settings, String ollamaBaseUrl, String telegramBaseUrl) {
        TransportServer transport = ServerMain.startTransport(config);
        RemoteActionExecutor executor = ServerMain.remoteExecutor(config, transport);
        RemoteClipboardTool clipboard = ServerMain.remoteClipboard(config, transport);

        AgentAssembly.Options options = new AgentAssembly.Options(ollamaBaseUrl, telegramBaseUrl,
                java.time.Clock.systemDefaultZone(), null, true, executor, clipboard, false);
        AgentAssembly app = AgentAssembly.build(config, settings, options);

        RemoteVoiceIngest voice = null;
        try {
            voice = new RemoteVoiceIngest(SttConfig.from(config.section(SttConfig.SECTION)), app.agentSwitch(), app::handleVoice);
            transport.addListener(new VoiceReceiver(voice));
        } catch (RuntimeException e) {
            log.error("Section [stt] is invalid, voice from clients will not be accepted: {}", e.getMessage());
        }

        ServerRuntime runtime = new ServerRuntime(config, transport, app, voice);
        transport.addListener(new TransportServer.Listener() {
            @Override
            public void onClientConnected(TransportServer.ClientInfo c) {
                runtime.notify("🖥 Client «" + c.name() + "» connected (" + c.remoteAddress() + ")");
            }

            @Override
            public void onClientDisconnected(TransportServer.ClientInfo c, int code, String reason) {
                runtime.notify("🖥 Client «" + c.name() + "» disconnected: "
                        + com.bebebe.agent.transport.Protocol.describeClose(code));
            }
        });
        if (app.telegram() != null) {
            app.telegram().attachHeadless(runtime::status, LogBuffer.global(),
                    Wiring.expandHome(config.section("logging").string("dir", "logs")).toAbsolutePath().toString());
        }
        return runtime;
    }

    private void notify(String text) {
        if (app.telegram() != null) {
            app.telegram().lastChatId().ifPresent(chat -> app.telegram().sendTo(chat, text));
        }
    }

    ServerStatus status() {
        OllamaStats.Snapshot s = app.llm().stats().snapshot();
        String state;
        if (s.lastSuccess().isEmpty() && s.lastFailure().isEmpty()) {
            state = "no model calls yet";
        } else if (s.available()) {
            state = "available -- last successful call " + TIME.format(s.lastSuccess().get());
        } else {
            state = "UNAVAILABLE -- failure " + TIME.format(s.lastFailure().get()) + ": " + s.lastError();
        }
        List<String> clients = new ArrayList<>();
        for (TransportServer.ClientInfo c : transport.clients()) {
            String caps = c.status() == null ? "status not sent yet" : String.join(" ", c.status().capabilities());
            String display = c.status() == null || c.status().display().isBlank() ? "" : ", " + c.status().display();
            clients.add(c.name() + " (" + c.remoteAddress() + display + "; " + caps + ")");
        }
        List<String> disk = new ArrayList<>();
        for (Path p : List.of(app.dataDir(), Wiring.expandHome(config.section("logging").string("dir", "logs")))) {
            try {
                FileStore store = Files.getFileStore(Files.exists(p) ? p : p.toAbsolutePath().getParent());
                disk.add(p.getFileName() + ": " + (store.getUsableSpace() / (1024 * 1024 * 1024)) + " GB free");
            } catch (Exception e) {
                disk.add(p + ": ?");
            }
        }
        return new ServerStatus(
                app.agentSwitch().isOn(),
                app.llm().displayName() + " · " + app.llm().model() + " · " + app.llm().endpoint(),
                s.available(),
                state,
                s.sessionCalls(), s.sessionTokens(), s.sessionFailures(), s.totalCalls(),
                clients,
                app.watchdog().map(w -> w.restarts()).orElse(0),
                app.build().describe(),
                app.updates().flatMap(UpdateChecker::last).map(UpdateChecker.Status::describe)
                        .orElse("not checked yet"),
                LogBuffer.global().lastError().map(e -> TIME.format(e.ts()) + " [" + e.subsystem() + "] " + e.message()),
                startedAt,
                disk,
                config.path() == null ? "?" : config.path().toAbsolutePath().toString());
    }

    TransportServer transport() {
        return transport;
    }

    AgentAssembly app() {
        return app;
    }

    Optional<RemoteVoiceIngest> voice() {
        return Optional.ofNullable(voice);
    }

    @Override
    public void close() {
        if (voice != null) {
            voice.close();
        }
        transport.close();
        app.close();
    }
}
