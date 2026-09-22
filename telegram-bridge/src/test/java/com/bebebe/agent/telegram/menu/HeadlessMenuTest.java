package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.logging.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessMenuTest {

    @TempDir
    Path tempDir;

    private MenuController controller;
    private AppSettings settings;
    private final LogBuffer logs = new LogBuffer(100);
    private final ServerStatus status = new ServerStatus(true, "Ollama · gpt-oss:120b · https://ollama.com",
            true, "available -- last successful call 12:00:00", 3, 1200, 0, 42,
            List.of("ноут (192.168.1.5, wayland-0; scripts clipboard voice)"), 0, "abc123 (main)",
            "build is up to date", Optional.of("12:01:00 [agent-core] something failed"),
            Instant.now().minusSeconds(3700), List.of("data: 120 GB free"), "/srv/bebebe/config/agent.toml");

    @BeforeEach
    void setUp() {
        settings = AppSettings.from(AppConfig.fromToml("""
                [llm.ollama]
                model = "gpt-oss:120b"
                """));
        controller = new MenuController(new AgentSwitch(true), settings, List::of,
                com.bebebe.agent.telegram.TestLibrary.inDirectory(tempDir),
                com.bebebe.agent.telegram.TestLibrary.NO_CONFIRM,
                com.bebebe.agent.telegram.TestLibrary.memoryIn(tempDir),
                com.bebebe.agent.telegram.TestLibrary.NO_MEMORY_ACTIONS);
    }

    private static LogEntry entry(String level, String message) {
        return new LogEntry(0, Instant.now(), level, "agent-core", "c.b.X", "t", "", message, Map.of(), null);
    }

    @Test
    void inStandaloneBuildStatusAndLogsAreStubsAndLogsNotInRoot() {
        assertFalse(controller.isHeadless());
        assertFalse(controller.visibleSections().contains(MenuSection.LOGS));
        assertTrue(controller.screenFor(MenuSection.STATUS).text().contains("application window"));
        assertTrue(controller.screenFor(MenuSection.LOGS).text().contains("application window"));
        assertTrue(controller.screenFor(MenuSection.SETTINGS).keyboard().buttonCount() > 3, "edit buttons present");
    }

    @Test
    void inHeadlessStatusShowsServerAndClients() {
        controller.attachHeadless(() -> status, logs, "/srv/bebebe/logs");

        assertTrue(controller.isHeadless());
        assertTrue(controller.visibleSections().contains(MenuSection.LOGS));
        String text = controller.screenFor(MenuSection.STATUS).text();
        assertTrue(text.contains("<b>on</b>"), text);
        assertTrue(text.contains("gpt-oss:120b"));
        assertTrue(text.contains("🟢"));
        assertTrue(text.contains("ноут (192.168.1.5"));
        assertTrue(text.contains("1 h 1 min"), text);
        assertTrue(text.contains("something failed"));
        assertTrue(text.contains("/srv/bebebe/config/agent.toml"));
        assertTrue(text.contains("over SSH"));
    }

    @Test
    void logsAreFilteredByLevelAndHintAtFiles() {
        controller.attachHeadless(() -> status, logs, "/srv/bebebe/logs");
        logs.add(entry("INFO", "ordinary line"));
        logs.add(entry("WARN", "warning"));
        logs.add(entry("ERROR", "error &lt;html&gt;"));

        MenuScreen warnPlus = controller.screenFor(MenuSection.LOGS);
        assertTrue(warnPlus.text().contains("warning"));
        assertTrue(warnPlus.text().contains("error"));
        assertFalse(warnPlus.text().contains("ordinary line"), "WARN+ by default");
        assertTrue(warnPlus.text().contains("tail -f /srv/bebebe/logs/agent.log"));

        MenuScreen all = controller.handle(CallbackData.logs("a", 0)).screen();
        assertTrue(all.text().contains("ordinary line"));
        MenuScreen errors = controller.handle(CallbackData.logs("e", 0)).screen();
        assertFalse(errors.text().contains("warning"));
        assertTrue(errors.text().contains("&amp;lt;html&amp;gt;"), "HTML escaped: " + errors.text());
        assertTrue(CallbackData.logs("w", 99).byteSize() <= CallbackData.WARN_BYTES);
    }

    @Test
    void inHeadlessSettingsAreReadOnlyAndButtonsChangeNothing() {
        controller.attachHeadless(() -> status, logs, "logs");

        MenuScreen screen = controller.screenFor(MenuSection.SETTINGS);
        assertTrue(screen.text().contains("over SSH"), screen.text());
        assertEquals(3, screen.keyboard().buttonCount(), "backup + back + close, no edit buttons");

        MenuResponse attempt = controller.handle(CallbackData.liveReplies(true));
        assertFalse(settings.liveReplies(), "pressing a stale button changes nothing");
        assertTrue(attempt.toast().contains("SSH"));
    }
}
