package com.bebebe.agent.ui;

import atlantafx.base.theme.Styles;
import com.bebebe.agent.i18n.Messages;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The building blocks every screen is made of -- and, because every screen is made of them,
 * the place where interface text gets translated.
 */
final class Ui {

    static final double PAGE_PADDING = 24;
    static final double GAP = 12;

    static final double READABLE_WIDTH = 640;

    private Ui() {
    }

    static VBox page(Node... children) {
        VBox page = new VBox(GAP, children);
        page.setPadding(new Insets(PAGE_PADDING));
        page.setFillWidth(true);
        return page;
    }

    static VBox header(String title, String subtitle) {
        Label t = new Label(Messages.t(title));
        t.getStyleClass().add(Styles.TITLE_2);
        VBox box = new VBox(4, t);
        if (subtitle != null && !subtitle.isBlank()) {
            box.getChildren().add(hint(subtitle));
        }
        VBox.setMargin(box, new Insets(0, 0, 4, 0));
        return box;
    }

    static Label section(String text) {
        Label l = new Label(Messages.t(text));
        l.getStyleClass().addAll(Styles.TITLE_4);
        VBox.setMargin(l, new Insets(8, 0, 0, 0));
        return l;
    }

    static Label caption(String text) {
        Label l = new Label(Messages.t(text));
        l.getStyleClass().add(Styles.TEXT_MUTED);
        return l;
    }

    static Label hint(String text) {
        Label l = new Label(Messages.t(text));
        l.getStyleClass().add(Styles.TEXT_SUBTLE);
        l.setWrapText(true);
        l.setMaxWidth(READABLE_WIDTH);
        return l;
    }

    static Label status() {
        Label l = new Label();
        l.getStyleClass().add(Styles.TEXT_SUBTLE);
        l.setWrapText(true);
        return l;
    }

    static Separator separator() {
        Separator s = new Separator();
        VBox.setMargin(s, new Insets(4, 0, 4, 0));
        return s;
    }

    static Region spacer() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }
}
