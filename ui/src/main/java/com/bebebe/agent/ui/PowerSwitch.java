package com.bebebe.agent.ui;

import javafx.animation.FillTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Cursor;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.Consumer;

final class PowerSwitch extends StackPane {

    static final int MILLIS = 220;
    private static final double W = 46;
    private static final double H = 24;
    private static final double R = 9;
    private static final double TRAVEL = W - H;

    private static final Color ON = Color.web("#238636");
    private static final Color OFF = Color.web("#da3633");

    private final Rectangle track = new Rectangle(W, H);
    private final Circle thumb = new Circle(R, Color.WHITE);
    private boolean on;
    private boolean first = true;

    PowerSwitch(Consumer<Boolean> onRequest) {
        track.setArcWidth(H);
        track.setArcHeight(H);
        track.setFill(OFF);
        thumb.setTranslateX(-TRAVEL / 2);
        getChildren().addAll(track, thumb);
        setMaxSize(W, H);
        setCursor(Cursor.HAND);
        setOnMouseClicked(e -> {
            if (!isDisabled()) {
                onRequest.accept(!on);
            }
        });
    }

    void show(boolean value) {
        if (!first && value == on) {
            return;
        }
        on = value;
        double x = on ? TRAVEL / 2 : -TRAVEL / 2;
        if (first) {
            first = false;
            thumb.setTranslateX(x);
            track.setFill(on ? ON : OFF);
            return;
        }
        TranslateTransition move = new TranslateTransition(Duration.millis(MILLIS), thumb);
        move.setToX(x);
        move.setInterpolator(Interpolator.EASE_BOTH);
        FillTransition fill = new FillTransition(Duration.millis(MILLIS), track, (Color) track.getFill(), on ? ON : OFF);
        new ParallelTransition(move, fill).play();
    }

    void setBusy(boolean busy) {
        setDisable(busy);
        setOpacity(busy ? 0.55 : 1.0);
    }
}
