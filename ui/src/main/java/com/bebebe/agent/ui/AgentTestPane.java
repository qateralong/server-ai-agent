package com.bebebe.agent.ui;

import atlantafx.base.theme.Styles;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmRequest;
import com.bebebe.agent.llm.LlmResponse;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentTestPane extends VBox {

    static final String TITLE = "Agent test";

    private static final Logger log = LoggerFactory.getLogger(AgentTestPane.class);

    private static final String DEFAULT_PROMPT =
            "Answer in one short sentence: why is the sky blue?";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ollama-test");
        thread.setDaemon(true);
        return thread;
    });

    private final TextArea promptArea = new TextArea(DEFAULT_PROMPT);
    private final TextArea responseArea = new TextArea();
    private final Label statusLabel = new Label("Ready for a request");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button sendButton = new Button("Send");
    private final Button pingButton = new Button("Check connection");

    public AgentTestPane() {
        VBox header = Ui.header(TITLE, "A direct request to the model bypassing the agent loop: check the connection, "
                + "key and speed. Goes through the provider selected in Settings.");

        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(4);

        responseArea.setWrapText(true);
        responseArea.setEditable(false);
        responseArea.setPromptText("The model reply will appear here");
        VBox.setVgrow(responseArea, Priority.ALWAYS);

        sendButton.getStyleClass().add(Styles.ACCENT);
        sendButton.setOnAction(e -> send());
        pingButton.setOnAction(e -> ping());

        spinner.setVisible(false);
        spinner.setPrefSize(18, 18);

        HBox controls = new HBox(10, sendButton, pingButton, spinner, statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);

        setSpacing(Ui.GAP);
        setPadding(new Insets(Ui.PAGE_PADDING));
        getChildren().addAll(
                header,
                Ui.caption("Prompt"),
                promptArea,
                controls,
                new Label("Reply:"),
                responseArea);
    }

    private void send() {
        String prompt = promptArea.getText();
        if (prompt == null || prompt.isBlank()) {
            setStatus("Prompt is empty", true);
            return;
        }
        run("Asking the model...", () -> {
            LlmProvider llm = client();
            LlmResponse response = llm.chat(new LlmRequest("", java.util.List.of(), prompt, null, null));
            Double speed = response.tokensPerSecond();
            String status = speed == null
                    ? "Done (%s, model: %s)".formatted(llm.displayName(), response.model())
                    : "Done (%s, model: %s, %.1f tok/s)".formatted(llm.displayName(), response.model(), speed);
            return new Result(response.text(), status);
        });
    }

    private void ping() {
        run("Checking connection...", () -> {
            LlmProvider llm = client();
            java.util.List<String> models = llm.listModels();
            return new Result(
                    String.join("\n", models),
                    llm.displayName() + " · " + llm.endpoint() + " -- OK, models: " + models.size());
        });
    }

    private void run(String busyStatus, ThrowingSupplier work) {
        setBusy(true);
        setStatus(busyStatus, false);

        Task<Result> task = new Task<>() {
            @Override
            protected Result call() throws Exception {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            Result result = task.getValue();
            responseArea.setText(result.text());
            setStatus(result.status(), false);
            setBusy(false);
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            Throwable error = task.getException();
            log.error("Model request failed", error);
            responseArea.setText(describe(error));
            setStatus("Error", true);
            setBusy(false);
        }));

        executor.execute(task);
    }

    private LlmProvider client() {
        return UiContext.llm();
    }

    private static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            sb.append(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()).append('\n');
        }
        return sb.toString().strip();
    }

    private void setBusy(boolean busy) {
        spinner.setVisible(busy);
        sendButton.setDisable(busy);
        pingButton.setDisable(busy);
    }

    private void setStatus(String text, boolean danger) {
        statusLabel.setText(text);
        statusLabel.getStyleClass().removeAll(Styles.DANGER, Styles.TEXT_MUTED);
        statusLabel.getStyleClass().add(danger ? Styles.DANGER : Styles.TEXT_MUTED);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private record Result(String text, String status) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Result get() throws Exception;
    }
}
