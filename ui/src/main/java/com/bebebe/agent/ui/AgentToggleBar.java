package com.bebebe.agent.ui;

import com.bebebe.agent.i18n.Messages;
import atlantafx.base.theme.Styles;
import com.bebebe.agent.core.ActivityMonitor;
import com.bebebe.agent.core.AgentState;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.watchdog.HeartbeatSource;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class AgentToggleBar extends VBox {

    private static final Logger log = LoggerFactory.getLogger(AgentToggleBar.class);
    private static final int POLL_MS = 300;

    private final AgentSwitch agentSwitch;
    private final ActivityMonitor activity;
    private final Circle indicator = new Circle(6);
    private final Label stateLabel = new Label();
    private final Label activityLabel = new Label();
    private final Label hintLabel = new Label();
    private final PowerSwitch powerSwitch;

    private final Consumer<AgentState> listener = state -> Platform.runLater(() -> render(state));
    private final ParallelTransition pulse;
    private final Timeline poll = new Timeline(new KeyFrame(Duration.millis(POLL_MS), e -> pollActivity()));
    private boolean pulsing;
    private boolean labelShown;

    private final ExecutorService switcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-toggle");
        thread.setDaemon(true);
        return thread;
    });

    public AgentToggleBar(AgentSwitch agentSwitch) {
        this(agentSwitch, null);
    }

    public AgentToggleBar(AgentSwitch agentSwitch, ActivityMonitor activity) {
        this.agentSwitch = agentSwitch;
        this.activity = activity;

        Label title = new Label(Messages.t("Agent"));
        title.getStyleClass().add(Styles.TEXT_BOLD);
        stateLabel.getStyleClass().add(Styles.TEXT_BOLD);
        activityLabel.getStyleClass().addAll(Styles.TEXT_SUBTLE);
        activityLabel.setOpacity(0);
        hintLabel.getStyleClass().add(Styles.TEXT_SUBTLE);

        powerSwitch = new PowerSwitch(this::requestSwitch);

        ScaleTransition scale = new ScaleTransition(Duration.millis(900), indicator);
        scale.setFromX(1);
        scale.setFromY(1);
        scale.setToX(1.45);
        scale.setToY(1.45);
        FadeTransition fade = new FadeTransition(Duration.millis(900), indicator);
        fade.setFromValue(1);
        fade.setToValue(0.4);
        pulse = new ParallelTransition(scale, fade);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.setInterpolator(Interpolator.EASE_BOTH);

        HBox row = new HBox(10, indicator, title, stateLabel, activityLabel, Ui.spacer(), hintLabel, powerSwitch);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, Ui.PAGE_PADDING, 12, Ui.PAGE_PADDING));
        getChildren().addAll(row, new Separator());

        agentSwitch.addListener(listener);
        render(agentSwitch.state());
        if (activity != null) {
            poll.setCycleCount(Timeline.INDEFINITE);
            poll.play();
        }
    }

    private void requestSwitch(boolean wantOn) {
        powerSwitch.setBusy(true);
        Transitions.crossfadeText(hintLabel, wantOn ? "switching on..." : "switching off...");
        switcher.execute(() -> {
            try {
                if (wantOn) {
                    agentSwitch.turnOn();
                } else {
                    agentSwitch.turnOff();
                }
            } finally {
                Platform.runLater(() -> {
                    powerSwitch.setBusy(false);
                    render(agentSwitch.state());
                });
            }
        });
    }

    private void render(AgentState state) {
        boolean on = state.isOn();
        indicator.setStyle("-fx-fill: " + (on ? "-color-success-emphasis" : "-color-danger-emphasis") + ";");
        Transitions.crossfadeText(stateLabel, on ? "on" : "off");
        Transitions.crossfadeText(hintLabel, on ? "processing messages" : "messages are not processed");
        powerSwitch.show(on);
        if (!on) {
            setPulsing(false, null);
        }
        log.debug("GUI toggle redrawn: {}", state.label());
    }

    private void pollActivity() {
        Optional<HeartbeatSource.InFlight> inFlight = activity.inFlight();
        if (inFlight.isEmpty()) {
            setPulsing(false, null);
            return;
        }
        HeartbeatSource.InFlight f = inFlight.get();
        String what = f.busy().map(b -> b.reason().startsWith("model reply") ? "thinking..."
                : "running " + b.reason() + "...").orElse("handling a request...");
        setPulsing(true, what);
    }

    private void setPulsing(boolean value, String what) {
        if (value) {
            if (!labelShown) {
                labelShown = true;
                activityLabel.setText(Messages.t("· ") + what);
                Transitions.fadeIn(activityLabel);
            } else {
                Transitions.crossfadeText(activityLabel, "· " + what);
            }
        } else {
            labelShown = false;
        }
        if (value == pulsing) {
            return;
        }
        pulsing = value;
        if (value) {
            pulse.playFromStart();
        } else {
            pulse.stop();

            ScaleTransition rest = new ScaleTransition(Transitions.IN, indicator);
            rest.setToX(1);
            rest.setToY(1);
            FadeTransition solid = new FadeTransition(Transitions.IN, indicator);
            solid.setToValue(1);
            FadeTransition hide = new FadeTransition(Transitions.IN, activityLabel);
            hide.setToValue(0);
            new ParallelTransition(rest, solid, hide).play();
        }
    }

    public void dispose() {
        poll.stop();
        pulse.stop();
        agentSwitch.removeListener(listener);
        switcher.shutdownNow();
    }
}
