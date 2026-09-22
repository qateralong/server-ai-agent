package com.bebebe.agent.ui;

import com.bebebe.agent.i18n.Messages;
import atlantafx.base.theme.Styles;
import com.bebebe.agent.memory.Persona;
import com.bebebe.agent.memory.PersonaStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class PersonasPane extends BorderPane {

    public static final String TITLE = "Personas";

    private static final Logger log = LoggerFactory.getLogger(PersonasPane.class);

    private final PersonaStore store;
    private final ListView<Persona> list = new ListView<>();
    private final TextField name = new TextField();
    private final TextArea prompt = new TextArea();
    private final Label status = new Label();
    private final Button activate = new Button(Messages.t("Make active"));
    private final Button delete = new Button(Messages.t("Delete"));
    private final Runnable listener = () -> Platform.runLater(this::reload);

    private Long editingId;

    public PersonasPane(PersonaStore store) {
        this.store = store;

        VBox header = Ui.header(TITLE, "The active persona's text is mixed into the system prompt of every reply. "
                + "Exactly one is active; switching takes effect immediately.");

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Persona item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayLine());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> select(selected));
        list.setPrefWidth(220);

        name.setPromptText(Messages.t("Persona name"));
        prompt.setPromptText(Messages.t("System prompt text: who the agent is and how it answers"));
        prompt.setWrapText(true);
        VBox.setVgrow(prompt, Priority.ALWAYS);

        Button save = new Button(Messages.t("Save"));
        save.getStyleClass().add(Styles.ACCENT);
        save.setOnAction(e -> save());
        Button create = new Button(Messages.t("New"));
        create.setOnAction(e -> startNew());
        activate.setOnAction(e -> ifSelected(id -> {
            store.activate(id);
            status.setText(Messages.t("Active: ") + name.getText());
        }));
        delete.getStyleClass().add(Styles.DANGER);
        delete.setOnAction(e -> ifSelected(id -> {
            try {
                store.delete(id);
                startNew();
                status.setText(Messages.t("Deleted"));
            } catch (IllegalStateException ex) {
                status.setText(ex.getMessage());
            }
        }));
        status.getStyleClass().add(Styles.TEXT_SUBTLE);

        HBox buttons = new HBox(10, save, create, activate, delete);
        VBox form = new VBox(10, name, prompt, buttons, status);
        form.setPadding(new Insets(0, 0, 0, 16));
        HBox.setHgrow(form, Priority.ALWAYS);

        HBox body = new HBox(list, form);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox content = Ui.page(header, Ui.separator(), body);
        VBox.setVgrow(body, Priority.ALWAYS);
        setCenter(content);

        store.addListener(listener);
        reload();
        startNew();
    }

    private void reload() {
        Long keep = editingId;
        List<Persona> all = store.all();
        list.getItems().setAll(all);
        if (keep != null) {
            all.stream().filter(p -> p.id() == keep).findFirst()
                    .ifPresent(p -> list.getSelectionModel().select(p));
        }
    }

    private void select(Persona p) {
        if (p == null) {
            return;
        }
        editingId = p.id();
        name.setText(p.name());
        prompt.setText(p.prompt());
        activate.setDisable(p.active());
        status.setText(p.active() ? "Active persona" : "");
    }

    private void startNew() {
        editingId = null;
        list.getSelectionModel().clearSelection();
        name.clear();
        prompt.clear();
        activate.setDisable(true);
        status.setText(Messages.t("New persona: fill in the name and text, press \"Save\""));
    }

    private void save() {
        try {
            Persona saved = editingId == null
                    ? store.create(name.getText(), prompt.getText())
                    : store.update(editingId, name.getText(), prompt.getText());
            editingId = saved.id();
            reload();
            status.setText(Messages.t("Saved: ") + saved.name());
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Cannot save persona", e);
            status.setText(Messages.t("Error: ") + e.getMessage());
        }
    }

    private void ifSelected(java.util.function.LongConsumer action) {
        if (editingId == null) {
            status.setText(Messages.t("Select a persona in the list first"));
            return;
        }
        action.accept(editingId);
    }

    public void dispose() {

    }
}
