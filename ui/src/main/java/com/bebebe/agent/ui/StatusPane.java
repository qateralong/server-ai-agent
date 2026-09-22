package com.bebebe.agent.ui;

import atlantafx.base.theme.Styles;
import com.bebebe.agent.core.AgentState;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.logging.LogEntry;
import com.bebebe.agent.ollama.OllamaStats;
import com.bebebe.agent.watchdog.UpdateChecker;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Consumer;

public final class StatusPane extends ScrollPane {

    static final String TITLE = "Status";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");

    private final AgentSwitch agentSwitch;
    private final UiServices services;
    private final Label stateValue = new Label();
    private final Label stateHint = new Label();
    private final Label lastError = new Label("—");
    private final Label ollamaState = new Label("—");
    private final Label ollamaSession = new Label("—");
    private final Label ollamaTotal = new Label("—");
    private final Label disk = new Label("—");
    private final Label version = new Label("—");
    private final Label updates = new Label("—");
    private final Label watchdog = new Label("—");
    private final Label backupStatus = new Label();
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(2), e -> refreshAll()));
    private final Consumer<AgentState> listener = state -> Platform.runLater(() -> render(state));

    public StatusPane(AgentSwitch agentSwitch, UiServices services) {
        this.agentSwitch = agentSwitch;
        this.services = services;

        VBox header = Ui.header(TITLE, "State of the process and external services. Only in the window: "
                + "if there is no network, a Telegram message about it would not arrive.");
        stateValue.getStyleClass().add(Styles.TITLE_4);
        stateHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        stateHint.setWrapText(true);
        for (Label l : new Label[] {lastError, ollamaState, ollamaSession, ollamaTotal, disk, version, updates, watchdog}) {
            l.setWrapText(true);
        }
        lastError.getStyleClass().add(Styles.WARNING);

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(8);
        int row = 0;
        row = add(grid, row, "Last error", lastError);
        row = add(grid, row, "Model", ollamaState);
        row = add(grid, row, "This session", ollamaSession);
        row = add(grid, row, "Total", ollamaTotal);
        row = add(grid, row, "Disk", disk);
        row = add(grid, row, "Version", version);
        row = add(grid, row, "Updates", updates);
        row = add(grid, row, "Watchdog", watchdog);

        Button checkUpdates = new Button("Check for updates");
        checkUpdates.setOnAction(e -> checkUpdatesNow());
        checkUpdates.setDisable(services == null || services.updates() == null);
        Button backup = new Button("Export/backup...");
        backup.getStyleClass().add(Styles.ACCENT);
        backup.setOnAction(e -> backup());
        backup.setDisable(services == null || services.backup() == null);
        Button quit = new Button("Quit the agent");
        quit.getStyleClass().add(Styles.DANGER);
        quit.setOnAction(e -> {
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "Stop the whole process? Telegram, reminders and voice will stop working until the next launch. "
                            + "The window's close button, on the contrary, only hides the window.",
                    javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            confirm.setHeaderText("Quit the agent");
            confirm.showAndWait().filter(b -> b == javafx.scene.control.ButtonType.OK)
                    .ifPresent(b -> MainWindow.quit());
        });
        Label backupNote = new Label("The archive includes memory, personas, reminders, notes and the config. "
                + "Secrets (Ollama key, bot token) are excluded from the config -- after restoring they must be entered again.");
        backupNote.getStyleClass().add(Styles.TEXT_SUBTLE);
        backupNote.setWrapText(true);
        backupStatus.getStyleClass().add(Styles.TEXT_SUBTLE);
        backupStatus.setWrapText(true);

        VBox content = Ui.page(
                header,
                Ui.section("Agent"), stateValue, stateHint,
                Ui.separator(),
                Ui.section("Services and resources"), grid,
                Ui.separator(),
                Ui.section("Maintenance"),
                new HBox(10, checkUpdates, backup, Ui.spacer(), quit), backupNote, backupStatus);
        setContent(content);
        setFitToWidth(true);

        agentSwitch.addListener(listener);
        render(agentSwitch.state());
        refreshAll();
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
    }

    private static int add(GridPane grid, int row, String name, Label value) {
        grid.add(Ui.caption(name), 0, row);
        grid.add(value, 1, row);
        return row + 1;
    }

    private void render(AgentState state) {
        boolean on = state == AgentState.ON;
        Transitions.crossfadeText(stateValue, on ? "On" : "Off");
        stateValue.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
        stateValue.getStyleClass().add(on ? Styles.SUCCESS : Styles.DANGER);
        stateHint.setText(on ? "Messages are processed, reminders tick."
                : "Incoming messages are ignored; the menu and window work. Switch on with the button above or ⚡ Power in Telegram.");
    }

    private void refreshAll() {
        lastError.setText(LogBuffer.global().lastError().map(this::describeError).orElse("none"));
        if (services == null) {
            return;
        }
        if (services.llm() != null) {
            OllamaStats.Snapshot s = services.llm().stats().snapshot();
            String who = services.llm().displayName() + " · " + services.llm().model() + " · " + services.llm().endpoint() + ": ";
            String state;
            if (s.lastSuccess().isEmpty() && s.lastFailure().isEmpty()) {
                state = "no calls yet";
            } else if (s.available()) {
                state = "available -- last successful call " + time(s.lastSuccess().get())
                        + s.lastFailure().map(f -> ", last failure " + time(f)).orElse("");
            } else {
                state = "UNAVAILABLE -- failure " + time(s.lastFailure().get()) + ": " + s.lastError()
                        + s.lastSuccess().map(ok -> " (last success " + time(ok) + ")").orElse("");
            }
            ollamaState.setText(who + state);
            ollamaState.getStyleClass().removeAll(Styles.SUCCESS, Styles.DANGER);
            ollamaState.getStyleClass().add(s.available() ? Styles.SUCCESS : Styles.DANGER);
            ollamaSession.setText(s.sessionCalls() + " calls, " + s.sessionTokens() + " tokens, failures: "
                    + s.sessionFailures() + " (since " + time(s.sessionStarted()) + ")");
            ollamaTotal.setText(s.totalCalls() + " calls, " + s.totalTokens() + " tokens, failures: " + s.totalFailures());
        }
        disk.setText(diskLine());
        if (services.build() != null) {
            version.setText(services.build().describe()
                    + (services.build().commitTime().isEmpty() ? "" : ", commit from " + services.build().commitTime().substring(0, 10)));
        }
        if (services.updates() != null) {
            updates.setText(services.updates().last().map(UpdateChecker.Status::describe)
                    .orElse("not checked yet (first check half a minute after start)"));
        } else {
            updates.setText("disabled");
        }
        if (services.watchdogRestarts() != null) {
            int n = services.watchdogRestarts().getAsInt();
            watchdog.setText(n == 0 ? "no hangs" : "worker thread restarts: " + n);
        }
    }

    private String describeError(LogEntry e) {
        return time(e.ts()) + " [" + e.subsystem() + "] " + e.message() + (e.error() == null ? "" : " — " + e.error());
    }

    private String diskLine() {
        StringBuilder sb = new StringBuilder();
        for (Path p : services.diskPaths()) {
            try {
                Path existing = p;
                while (existing != null && !Files.exists(existing)) {
                    existing = existing.getParent();
                }
                if (existing == null) {
                    continue;
                }
                FileStore store = Files.getFileStore(existing);
                long free = store.getUsableSpace() / (1024 * 1024);
                long total = store.getTotalSpace() / (1024 * 1024);
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p).append(": ").append(free >= 1024 ? (free / 1024) + " GB" : free + " MB")
                        .append(" free of ").append(total / 1024).append(" GB");
            } catch (IOException ignored) {

            }
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private void checkUpdatesNow() {
        updates.setText("checking...");
        Thread t = new Thread(() -> {
            UpdateChecker.Status status = services.updates().check();
            Platform.runLater(() -> updates.setText(status.describe()));
        }, "update-check-manual");
        t.setDaemon(true);
        t.start();
    }

    private void backup() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Where to save the archive");
        File dir = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (dir == null) {
            return;
        }
        backupStatus.setText("Building the archive...");
        Thread t = new Thread(() -> {
            String result;
            try {
                result = services.backup().run(dir.toPath());
            } catch (RuntimeException e) {
                result = "Error: " + e.getMessage();
            }
            String text = result;
            Platform.runLater(() -> backupStatus.setText(text));
        }, "backup");
        t.setDaemon(true);
        t.start();
    }

    private static String time(Instant instant) {
        return TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    public void dispose() {
        refresh.stop();
        agentSwitch.removeListener(listener);
    }
}
