package com.bebebe.agent.ui;

import com.bebebe.agent.i18n.Messages;
import atlantafx.base.theme.Styles;
import com.bebebe.agent.notes.ChecklistItem;
import com.bebebe.agent.notes.NoteDocument;
import com.bebebe.agent.notes.NoteKind;
import com.bebebe.agent.notes.NotesStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public final class NotesPane extends BorderPane {

    static final String TITLE = "Notes";

    private static final Logger log = LoggerFactory.getLogger(NotesPane.class);

    private final NotesStore store;
    private final ListView<NoteDocument> list = new ListView<>();
    private final VBox detail = new VBox(Ui.GAP);
    private final Label status = Ui.status();
    private Long selectedId;

    public NotesPane(NotesStore store) {
        this.store = store;

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(NoteDocument item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayLine());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, doc) -> {
            selectedId = doc == null ? null : doc.id();
            renderDetail(doc);
        });
        list.setPrefWidth(260);

        Button newList = new Button(Messages.t("📋 New list"));
        newList.setOnAction(e -> create(NoteKind.LIST));
        Button newNote = new Button(Messages.t("📝 New note"));
        newNote.setOnAction(e -> create(NoteKind.NOTE));
        Button refresh = new Button(Messages.t("Refresh"));
        refresh.setOnAction(e -> reload());
        HBox actions = new HBox(8, newList, newNote, refresh);

        VBox left = new VBox(Ui.GAP, actions, list);
        VBox.setVgrow(list, Priority.ALWAYS);

        ScrollPane right = new ScrollPane(detail);
        right.setFitToWidth(true);
        detail.setPadding(new Insets(0, 0, 0, 16));
        HBox body = new HBox(left, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox page = Ui.page(
                Ui.header(TITLE, "The same files edited by notes_tool, the 📝 section in Telegram and Obsidian: "
                        + store.dir() + ". Every change is a commit in this directory's git."),
                body, status);
        VBox.setVgrow(body, Priority.ALWAYS);
        setCenter(page);

        store.addListener(() -> Platform.runLater(this::reload));
        reload();
    }

    private void reload() {
        List<NoteDocument> all = store.all();
        list.getItems().setAll(all);
        if (selectedId != null) {
            all.stream().filter(d -> d.id() == selectedId).findFirst()
                    .ifPresentOrElse(d -> {
                        list.getSelectionModel().select(d);
                        renderDetail(d);
                    }, () -> renderDetail(null));
        } else {
            renderDetail(null);
        }
    }

    private void renderDetail(NoteDocument doc) {
        detail.getChildren().clear();
        if (doc == null) {
            detail.getChildren().add(Ui.hint(list.getItems().isEmpty()
                    ? "Nothing yet. Create a list or a note -- or tell the agent «запиши в список покупок молоко»."
                    : "Select a document on the left."));
            return;
        }
        Label title = new Label(doc.title());
        title.getStyleClass().add(Styles.TITLE_3);
        detail.getChildren().add(title);
        if (!doc.tags().isEmpty()) {
            detail.getChildren().add(Ui.hint("#" + String.join(" #", doc.tags())));
        }
        if (doc.kind() == NoteKind.LIST) {
            renderList(doc);
        } else {
            renderNote(doc);
        }
        Button delete = new Button(Messages.t("🗑 Delete ") + (doc.kind() == NoteKind.LIST ? "list" : "note"));
        delete.getStyleClass().add(Styles.DANGER);
        delete.setOnAction(e -> {
            store.delete(doc.id());
            selectedId = null;
            status.setText(Messages.t("Deleted: ") + doc.title());
        });
        detail.getChildren().addAll(Ui.separator(), delete);
    }

    private void renderList(NoteDocument doc) {
        VBox items = new VBox(6);
        for (ChecklistItem item : doc.items()) {
            CheckBox box = new CheckBox(item.text() + (item.jobId() != null ? "  ⏰" : ""));
            box.setSelected(item.done());
            box.setOnAction(e -> run(() -> store.setDone(doc.id(), item.index(), box.isSelected()),
                    (box.isSelected() ? "Done: " : "Unchecked: ") + item.text()));
            Button remove = new Button(Messages.t("✖"));
            remove.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT);
            remove.setOnAction(e -> run(() -> store.removeItem(doc.id(), item.index()), "Item removed: " + item.text()));
            HBox row = new HBox(8, box, Ui.spacer(), remove);
            row.setAlignment(Pos.CENTER_LEFT);
            items.getChildren().add(row);
        }
        if (doc.items().isEmpty()) {
            items.getChildren().add(Ui.hint("The list is empty."));
        }
        TextField newItem = new TextField();
        newItem.setPromptText(Messages.t("New item -- press Enter to add"));
        newItem.setOnAction(e -> {
            String text = newItem.getText().strip();
            if (!text.isEmpty()) {
                run(() -> store.addItem(doc.id(), text), "Added: " + text);
            }
        });
        HBox.setHgrow(newItem, Priority.ALWAYS);
        detail.getChildren().addAll(items, newItem);
    }

    private void renderNote(NoteDocument doc) {
        TextArea text = new TextArea(doc.body());
        text.setWrapText(true);
        text.setPrefRowCount(18);
        Button save = new Button(Messages.t("Save"));
        save.getStyleClass().add(Styles.ACCENT);
        save.setOnAction(e -> run(() -> store.replaceBody(doc.id(), text.getText()), "Saved: " + doc.title()));
        detail.getChildren().addAll(text, new HBox(save));
    }

    private void create(NoteKind kind) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(kind == NoteKind.LIST ? "New list" : "New note");
        dialog.setHeaderText(null);
        dialog.setContentText(Messages.t("Title:"));
        Optional<String> name = dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
        name.ifPresent(title -> run(() -> {
            NoteDocument created = kind == NoteKind.LIST
                    ? store.createList(title, List.of())
                    : store.createNote(title, "", List.of());
            selectedId = created.id();
        }, "Created: " + title));
    }

    private void run(Runnable action, String done) {
        try {
            action.run();
            status.setText(done);
        } catch (RuntimeException e) {
            log.warn("Notes: {}", e.toString());
            status.setText(Messages.t("Error: ") + e.getMessage());
        }
    }
}
