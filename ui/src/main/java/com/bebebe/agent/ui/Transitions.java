package com.bebebe.agent.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

final class Transitions {

    static final Duration OUT = Duration.millis(120);
    static final Duration IN = Duration.millis(200);
    static final double SLIDE_PX = 12;

    private Transitions() {
    }

    static void swap(BorderPane host, Node next) {
        Node current = host.getCenter();
        if (current == next) {
            return;
        }
        Runnable show = () -> {
            next.setOpacity(0);
            next.setTranslateY(SLIDE_PX);
            host.setCenter(next);
            FadeTransition fade = new FadeTransition(IN, next);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(IN, next);
            slide.setToY(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            new ParallelTransition(fade, slide).play();
        };
        if (current == null) {
            show.run();
            return;
        }
        FadeTransition out = new FadeTransition(OUT, current);
        out.setToValue(0);
        out.setInterpolator(Interpolator.EASE_IN);
        out.setOnFinished(e -> {
            current.setOpacity(1);
            show.run();
        });
        out.play();
    }

    static void crossfadeText(javafx.scene.control.Labeled label, String text) {
        if (text.equals(label.getText())) {
            return;
        }
        if (label.getText() == null || label.getText().isEmpty()) {
            label.setText(text);
            fadeIn(label);
            return;
        }
        FadeTransition out = new FadeTransition(OUT, label);
        out.setToValue(0);
        FadeTransition in = new FadeTransition(IN, label);
        in.setToValue(1);
        out.setOnFinished(e -> label.setText(text));
        new SequentialTransition(out, in).play();
    }

    static void fadeIn(Node node) {
        node.setOpacity(0);
        FadeTransition in = new FadeTransition(IN, node);
        in.setToValue(1);
        in.play();
    }
}
