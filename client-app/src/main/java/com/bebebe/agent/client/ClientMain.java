package com.bebebe.agent.client;

import com.bebebe.agent.logging.AppLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class ClientMain {

    private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

    private ClientMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        AppLogging.bootstrap();
        Optional<Path> file = ClientConfig.locate();
        if (file.isEmpty()) {
            log.error("Client config not found. Looked in: {}. Example: config/client.example.toml; the values for "
                    + "[server] are printed by \"server-app pair\" on the server.", ClientConfig.candidates());
            System.exit(2);
        }
        ClientConfig config;
        try {
            config = ClientConfig.load(file.get());
        } catch (RuntimeException e) {
            log.error("Client config {} is invalid: {}", file.get(), e.getMessage());
            System.exit(2);
            return;
        }
        AppLogging.applyConfig(config.logDir(), config.logLevel(), Map.of());

        com.bebebe.agent.i18n.Messages.setLanguage(config.language());
        log.info("Client config: {}", config);

        ClientRuntime runtime = ClientRuntime.start(config, null, true, () -> System.exit(0));
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "shutdown"));
        Thread.currentThread().join();
    }
}
