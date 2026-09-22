package com.bebebe.agent.server;

import com.bebebe.agent.assembly.AgentAssembly;
import com.bebebe.agent.assembly.Wiring;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.logging.AppLogging;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.TransportConfig;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.remote.ClientSelector;
import com.bebebe.agent.transport.remote.RemoteActionExecutor;
import com.bebebe.agent.transport.remote.RemoteClipboardTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        AppLogging.bootstrap();
        AppConfig config = Wiring.readConfig();
        Wiring.applyLogging(config);
        if (config.path() == null) {
            log.error("Config not found -- the server has nothing to do without it. Put config.toml into one of the "
                    + "search locations (see README, section \"Server\") and restart.");
            System.exit(2);
        }
        if (args.length > 0) {

            System.exit(PairingCli.run(config, args));
        }
        log.info("Server mode: config {} (edited only in the file, read at startup)", config.path());

        AppSettings settings = AppSettings.from(config);
        log.info("Settings: {}", settings);

        ServerRuntime runtime = ServerRuntime.start(config, settings);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "shutdown"));
        log.info("Server started. Transport: wss://{}:{} (fingerprint {})",
                runtime.transport().config().bind(), runtime.transport().port(), runtime.transport().fingerprint());

        Thread.currentThread().join();
    }

    static TransportServer startTransport(AppConfig config) {
        TransportConfig transportConfig = TransportConfig.from(config.section(TransportConfig.SECTION));
        if (!transportConfig.enabled()) {
            log.warn("[transport] enabled = false, but this is the server -- starting the transport anyway: "
                    + "otherwise clients have nowhere to connect");
        }
        PairingTokens tokens = new PairingTokens(transportConfig.clientsFile());
        TransportServer server = new TransportServer(transportConfig, tokens);
        server.start();
        if (tokens.list().isEmpty()) {
            log.warn("No paired clients: scripts, clipboard and voice will be unavailable until a "
                    + "pairing token is issued (see README, section \"Server -> client pairing\")");
        }
        return server;
    }

    static RemoteActionExecutor remoteExecutor(AppConfig config, TransportServer server) {
        Duration scriptTimeout = config.section("scripts").seconds("timeout_seconds", Duration.ofSeconds(15));
        return new RemoteActionExecutor(server, selector(config, server), scriptTimeout);
    }

    static RemoteClipboardTool remoteClipboard(AppConfig config, TransportServer server) {
        return new RemoteClipboardTool(server, selector(config, server));
    }

    private static ClientSelector selector(AppConfig config, TransportServer server) {
        return new ClientSelector(server, config.section(TransportConfig.SECTION).string("default_client", ""));
    }

    static LogBuffer logs() {
        return LogBuffer.global();
    }
}
