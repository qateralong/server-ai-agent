package com.bebebe.agent.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackConfigTest {

    @Test
    void configParsesWithoutErrors() throws Exception {
        Path dir = Files.createTempDirectory("logback-test");
        System.setProperty(AppLogging.PROPERTY_DIR, dir.toString());
        try {
            URL config = getClass().getClassLoader().getResource("logback.xml");
            assertNotNull(config, "logback.xml not in classpath");

            LoggerContext context = freshContext();
            context.setName("test");
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);

            List<Status> errors = context.getStatusManager().getCopyOfStatusList().stream()
                    .filter(status -> status.getLevel() >= Status.WARN)
                    .toList();
            assertTrue(errors.isEmpty(), "logback complains about the config: " + errors);

            for (String name : List.of("agent-core", "script-runtime", "telegram-bridge",
                    "scheduler", "watchdog", "transport")) {
                context.getLogger("com.bebebe.agent." + toPackage(name)).info("probe");
            }
            context.stop();

            for (String file : List.of("agent.log", "agent-core.log", "script-runtime.log",
                    "telegram-bridge.log", "scheduler.log", "watchdog.log", "transport.log")) {
                assertTrue(Files.exists(dir.resolve(file)), "missing file " + file + " in " + dir);
            }
        } finally {
            System.clearProperty(AppLogging.PROPERTY_DIR);
        }
    }

    @Test
    void subsystemFilesAreWrittenInSameJsonFormat() throws Exception {
        Path dir = Files.createTempDirectory("logback-json");
        System.setProperty(AppLogging.PROPERTY_DIR, dir.toString());
        try {
            LoggerContext context = freshContext();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getClassLoader().getResource("logback.xml"));

            try (TraceContext.Scope ignored = TraceContext.open("трасса-1")) {
                context.getLogger("com.bebebe.agent.core.X").info("из ядра");
                context.getLogger("com.bebebe.agent.script.runtime.Y").info("из скриптов");
            }
            context.stop();

            String core = Files.readString(dir.resolve("agent-core.log"));
            String scripts = Files.readString(dir.resolve("script-runtime.log"));
            String all = Files.readString(dir.resolve("agent.log"));

            assertTrue(core.contains("\"trace_id\":\"трасса-1\""), "agent-core.log:\n" + core);
            assertTrue(scripts.contains("\"trace_id\":\"трасса-1\""), "script-runtime.log:\n" + scripts);
            assertTrue(core.contains("\"subsystem\":\"agent-core\""));
            assertTrue(scripts.contains("\"subsystem\":\"script-runtime\""));

            assertEquals(2, all.lines().filter(l -> l.contains("трасса-1")).count());

            assertEquals(1, core.lines().count(), core);
        } finally {
            System.clearProperty(AppLogging.PROPERTY_DIR);
        }
    }

    private static LoggerContext freshContext() {
        LoggerContext context = new LoggerContext();
        LoggerContext global = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.setMDCAdapter(global.getMDCAdapter());
        return context;
    }

    private static String toPackage(String subsystem) {
        return switch (subsystem) {
            case "agent-core" -> "core.T";
            case "script-runtime" -> "script.T";
            case "telegram-bridge" -> "telegram.T";
            default -> subsystem + ".T";
        };
    }
}
