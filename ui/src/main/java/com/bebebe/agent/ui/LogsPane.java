package com.bebebe.agent.ui;

import com.bebebe.agent.i18n.Messages;
import atlantafx.base.theme.Styles;
import com.bebebe.agent.config.ConfigFileWriter;
import com.bebebe.agent.logging.AppLogging;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.logging.LogEntry;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class LogsPane extends BorderPane {

    static final String TITLE = "Logs";

    private static final Logger log = LoggerFactory.getLogger(LogsPane.class);
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final int MAX_ROWS = 1500;

    private final Path configFile;
    private final ObservableList<LogEntry> rows = FXCollections.observableArrayList();
    private final ListView<LogEntry> list = new ListView<>(rows);
    private final ComboBox<String> minLevel = new ComboBox<>(FXCollections.observableArrayList("All levels", "DEBUG+", "INFO+", "WARN+", "ERROR"));
    private final ComboBox<String> subsystem = new ComboBox<>(FXCollections.observableArrayList("All modules"));
    private final TextField traceId = new TextField();
    private final TextField search = new TextField();
    private final CheckBox follow = new CheckBox(Messages.t("Follow"));
    private final ComboBox<String> logLevel = new ComboBox<>(FXCollections.observableArrayList(LEVELS));
    private final Label status = new Label();
    private final Consumer<LogEntry> listener = entry -> Platform.runLater(() -> onEntry(entry));

    public LogsPane(Path configFile) {
        this.configFile = configFile;

        VBox header = Ui.header(TITLE, "A live feed of the same lines that go to the JSON files in logs/. "
                + "Double-click a line to filter by its trace_id.");

        minLevel.getSelectionModel().select("INFO+");
        subsystem.getSelectionModel().selectFirst();
        traceId.setPromptText(Messages.t("trace_id"));
        traceId.setPrefColumnCount(12);
        search.setPromptText(Messages.t("message text"));
        search.setPrefColumnCount(18);
        follow.setSelected(true);
        Button clear = new Button(Messages.t("Reset filters"));
        clear.setOnAction(e -> {
            minLevel.getSelectionModel().select("INFO+");
            subsystem.getSelectionModel().selectFirst();
            traceId.clear();
            search.clear();
            reload();
        });
        minLevel.setOnAction(e -> reload());
        subsystem.setOnAction(e -> reload());
        traceId.textProperty().addListener((o, a, b) -> reload());
        search.textProperty().addListener((o, a, b) -> reload());

        logLevel.getSelectionModel().select(AppLogging.currentLevel());
        logLevel.setOnAction(e -> applyLevel(logLevel.getValue()));
        Label levelCaption = Ui.caption("Logging level:");

        HBox filters = new HBox(8, minLevel, subsystem, traceId, search, follow, clear);
        HBox levelRow = new HBox(8, levelCaption, logLevel, status);
        status.getStyleClass().add(Styles.TEXT_SUBTLE);

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item.displayLine() + (item.error() == null ? "" : "  ⟵ " + item.error()));
                setFont(Font.font("Monospaced", 12));
                setStyle(switch (item.level()) {
                    case "ERROR" -> "-fx-text-fill: -color-danger-fg;";
                    case "WARN" -> "-fx-text-fill: -color-warning-fg;";
                    case "DEBUG", "TRACE" -> "-fx-text-fill: -color-fg-subtle;";
                    default -> "";
                });
            }
        });
        list.setOnMouseClicked(e -> {
            LogEntry selected = list.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && selected != null && !selected.traceId().isEmpty()) {
                traceId.setText(selected.traceId());
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox top = new VBox(Ui.GAP, header, filters, levelRow);
        top.setPadding(new Insets(Ui.PAGE_PADDING, Ui.PAGE_PADDING, 8, Ui.PAGE_PADDING));
        setTop(top);
        setCenter(list);
        BorderPane.setMargin(list, new Insets(0, Ui.PAGE_PADDING, Ui.PAGE_PADDING, Ui.PAGE_PADDING));

        subsystem.getItems().addAll(List.of("agent-core", "memory-store", "notes-store", "ollama-client",
                "scheduler", "script-runtime", "stt-bridge", "telegram-bridge", "tools", "tts-bridge", "watchdog"));
        LogBuffer.global().addListener(listener);
        reload();
    }

    private Predicate<LogEntry> filter() {
        String lvl = minLevel.getValue();
        int min = switch (lvl == null ? "" : lvl) {
            case "DEBUG+" -> 1;
            case "INFO+" -> 2;
            case "WARN+" -> 3;
            case "ERROR" -> 4;
            default -> 0;
        };
        String sub = subsystem.getValue();
        String trace = traceId.getText() == null ? "" : traceId.getText().strip();
        String text = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        return e -> rank(e.level()) >= min
                && (sub == null || sub.startsWith("All") || sub.equals(e.subsystem()))
                && (trace.isEmpty() || e.traceId().contains(trace))
                && (text.isEmpty() || e.message().toLowerCase(Locale.ROOT).contains(text));
    }

    private static int rank(String level) {
        return switch (level) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "INFO" -> 2;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            default -> 2;
        };
    }

    private void reload() {
        rows.setAll(LogBuffer.global().snapshot(filter(), MAX_ROWS));
        if (follow.isSelected() && !rows.isEmpty()) {
            list.scrollTo(rows.size() - 1);
        }
    }

    private void onEntry(LogEntry entry) {
        if (!filter().test(entry)) {
            return;
        }
        rows.add(entry);
        if (rows.size() > MAX_ROWS) {
            rows.remove(0, rows.size() - MAX_ROWS);
        }
        if (follow.isSelected()) {
            list.scrollTo(rows.size() - 1);
        }
    }

    private void applyLevel(String level) {
        if (level == null) {
            return;
        }
        try {
            AppLogging.setLevel(level);
            if (configFile != null) {
                ConfigFileWriter.update(configFile, Map.of("logging.level", level));
                status.setText(Messages.t("Level ") + level + " applied and written to config");
            } else {
                status.setText(Messages.t("Level ") + level + " applied (no config -- not saved)");
            }
        } catch (RuntimeException e) {
            log.warn("Logging level not written to config: {}", e.getMessage());
            status.setText(Messages.t("Level ") + level + " applied but not written to config: " + e.getMessage());
        }
    }

    public void dispose() {
        LogBuffer.global().removeListener(listener);
    }
}
