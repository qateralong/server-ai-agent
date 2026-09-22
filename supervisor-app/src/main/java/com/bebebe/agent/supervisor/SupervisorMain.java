package com.bebebe.agent.supervisor;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.logging.AppLogging;
import com.bebebe.agent.assembly.AgentAssembly;
import com.bebebe.agent.assembly.Wiring;
import com.bebebe.agent.ui.UiServices;
import com.bebebe.agent.ui.MainWindow;
import com.bebebe.agent.ui.UiContext;
import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SupervisorMain {

    private static final Logger log = LoggerFactory.getLogger(SupervisorMain.class);

    private SupervisorMain() {
    }

    public static void main(String[] args) {
        AppLogging.bootstrap();

        AppConfig config = Wiring.readConfig();
        Wiring.applyLogging(config);

        com.bebebe.agent.ui.SingleInstance instance = new com.bebebe.agent.ui.SingleInstance();
        if (!instance.tryBecomePrimary()) {
            System.exit(0);
        }
        instance.onCommand(cmd -> {
            if (cmd.equals("show")) {
                UiContext.showWindow();
            }
        });

        AppSettings settings = AppSettings.from(config);
        log.info("Settings: {}", settings);

        AgentAssembly app = AgentAssembly.build(config, settings, AgentAssembly.Options.production());

        UiContext.install(app.agentSwitch(), settings, app.llm());
        UiContext.installPersonas(app.personas());
        UiContext.installNotes(app.notes());
        UiContext.installServices(new UiServices(app.llm(), app.build(), app.updates().orElse(null),
                dir -> app.backup().create(dir).summary(),
                java.util.List.of(app.dataDir(), Wiring.expandHome(config.section("logging").string("dir", "logs"))),
                config.path(), () -> app.watchdog().map(w -> w.restarts()).orElse(0), app.core().activity()));
        if (app.telegram() != null) {
            app.telegram().attachWindow(UiContext::showWindow);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.close();
            instance.close();
        }, "shutdown"));

        log.info("Starting the GUI");
        Application.launch(MainWindow.class, args);
    }

}
