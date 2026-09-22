package com.bebebe.agent.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.bebebe.agent.core.AgentSwitch;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class MainWindow extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainWindow.class);

    private static final List<Section> SECTIONS = List.of(
            new Section(StatusPane.TITLE, null),
            new Section(SettingsPane.TITLE, null),
            new Section(LogsPane.TITLE, null),
            new Section(PersonasPane.TITLE, null),
            new Section(NotesPane.TITLE, null),
            new Section(AgentTestPane.TITLE, null));

    private final BorderPane content = new BorderPane();
    private final AgentTestPane agentTestPane = new AgentTestPane();

    private AgentToggleBar toggleBar;
    private StatusPane statusPane;
    private SettingsPane settingsPane;
    private javafx.scene.Node personasPane;
    private LogsPane logsPane;
    private javafx.scene.Node notesPane;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        AgentSwitch agentSwitch = UiContext.agentSwitch();
        toggleBar = new AgentToggleBar(agentSwitch,
                UiContext.services() == null ? null : UiContext.services().activity());
        statusPane = new StatusPane(agentSwitch, UiContext.services());
        logsPane = new LogsPane(UiContext.services() == null ? null : UiContext.services().configFile());
        settingsPane = new SettingsPane(UiContext.settings(), UiContext.llm());
        notesPane = UiContext.notes() == null
                ? placeholder(new Section(NotesPane.TITLE, "Notes store is not connected."))
                : new NotesPane(UiContext.notes());
        personasPane = UiContext.personas() == null
                ? placeholder(new Section(PersonasPane.TITLE, "Persona store is not connected."))
                : new PersonasPane(UiContext.personas());

        BorderPane root = new BorderPane();
        root.setTop(toggleBar);
        root.setLeft(buildSideMenu());
        root.setCenter(content);

        Scene scene = new Scene(root, 1100, 720);
        stage.setTitle("Server AI Agent");
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);

        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(e -> {
            e.consume();
            stage.hide();
            log.info("Window hidden, the agent keeps running");
        });
        Runnable show = () -> Platform.runLater(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
        UiContext.onShowWindow(show);
        TrayIconSupport.install(show, MainWindow::quit);
        stage.show();

        log.info("Window shown, AtlantaFX PrimerDark theme");
    }

    public static void quit() {
        log.info("Shutting down at the user's request");
        Platform.exit();
        new Thread(() -> System.exit(0), "quit").start();
    }

    @Override
    public void stop() {
        agentTestPane.shutdown();
        toggleBar.dispose();
        statusPane.dispose();
        logsPane.dispose();
        settingsPane.dispose();
        log.info("Window closed");
    }

    private Region buildSideMenu() {
        ListView<Section> menu = new ListView<>();
        menu.getItems().setAll(SECTIONS);
        menu.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        menu.setPrefWidth(220);
        menu.setMinWidth(180);
        menu.getStyleClass().add(Styles.DENSE);
        menu.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Section item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
                setPadding(new Insets(10, 16, 10, 16));
            }
        });

        menu.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> showSection(selected));
        menu.getSelectionModel().selectFirst();

        return menu;
    }

    private void showSection(Section section) {
        if (section == null) {
            return;
        }
        log.debug("Section opened: {}", section.title());
        javafx.scene.Node next;
        if (section.description() != null) {
            next = placeholders.computeIfAbsent(section.title(), t -> placeholder(section));
        } else if (section.title().equals(StatusPane.TITLE)) {
            next = statusPane;
        } else if (section.title().equals(SettingsPane.TITLE)) {
            next = settingsPane;
        } else if (section.title().equals(PersonasPane.TITLE)) {
            next = personasPane;
        } else if (section.title().equals(LogsPane.TITLE)) {
            next = logsPane;
        } else if (section.title().equals(NotesPane.TITLE)) {
            next = notesPane;
        } else {
            next = agentTestPane;
        }

        Transitions.swap(content, next);
    }

    private final java.util.Map<String, javafx.scene.Node> placeholders = new java.util.HashMap<>();

    private Region placeholder(Section section) {
        Label todo = new Label("This screen is not implemented yet.");
        todo.getStyleClass().addAll(Styles.TEXT_MUTED, Styles.TEXT_ITALIC);
        return Ui.page(Ui.header(section.title(), section.description()), todo);
    }

    private record Section(String title, String description) {
    }
}
