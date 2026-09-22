package com.bebebe.agent.ui;

import com.bebebe.agent.i18n.Messages;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.config.ConfigException;
import com.bebebe.agent.config.SettingsField;
import com.bebebe.agent.llm.LlmProvider;
import com.bebebe.agent.llm.LlmProviders;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class SettingsPane extends ScrollPane {

    static final String TITLE = "Settings";

    private static final Logger log = LoggerFactory.getLogger(SettingsPane.class);

    private final AppSettings settings;
    private final LlmProvider llm;

    private final java.util.Map<String, String[]> draftSlots = new java.util.HashMap<>();
    private String draftProvider;
    private final ComboBox<String> provider = new ComboBox<>();
    private final TextField endpoint = new TextField();

    private final PasswordField botToken = new PasswordField();
    private final CheckBox showBotToken = new CheckBox(Messages.t("show"));
    private final TextField botTokenVisible = new TextField();

    private final PasswordField apiKey = new PasswordField();
    private final CheckBox showApiKey = new CheckBox(Messages.t("show"));
    private final TextField apiKeyVisible = new TextField();

    private final ComboBox<String> model = new ComboBox<>();
    private final Button loadModels = new Button(Messages.t("Load list"));

    private final TextField usernames = new TextField();
    private final ToggleSwitch proactiveHints = new ToggleSwitch();
    private final ToggleSwitch voiceReplies = new ToggleSwitch();
    private final ToggleSwitch liveReplies = new ToggleSwitch();
    private final ToggleSwitch typingIndicator = new ToggleSwitch();
    private final ToggleSwitch voiceInput = new ToggleSwitch();
    private final ComboBox<com.bebebe.agent.i18n.Language> language = new ComboBox<>();
    private final ToggleSwitch scriptsEnabled = new ToggleSwitch();

    private final Label status = new Label();
    private final Button save = new Button(Messages.t("Save"));
    private final Button reset = new Button(Messages.t("Discard changes"));

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "settings-io");
        thread.setDaemon(true);
        return thread;
    });

    private final Consumer<SettingsField> listener = field -> Platform.runLater(this::loadFromSettings);

    public SettingsPane(AppSettings settings, LlmProvider llm) {
        this.settings = settings;
        this.llm = llm;

        VBox content = Ui.page(
                Ui.header(TITLE, "The same values as in the ⚙️ Settings section of Telegram, plus secrets. "
                        + "File: " + settings.file().toAbsolutePath()),
                Ui.separator(),
                providerBlock(),
                Ui.separator(),
                secretsBlock(),
                Ui.separator(),
                liveBlock(),
                Ui.separator(),
                buttonsRow());

        setContent(content);
        setFitToWidth(true);

        settings.addListener(listener);
        loadFromSettings();
    }

    private Region secretsBlock() {
        Label caption = new Label(Messages.t("Secrets -- only here"));
        caption.getStyleClass().add(Styles.TEXT_BOLD);

        Label note = new Label(Messages.t("The Telegram menu has no such fields: messages stay "
                + "in the chat history and on Telegram servers."));
        note.getStyleClass().add(Styles.TEXT_SUBTLE);
        note.setWrapText(true);

        Label warning = new Label(Messages.t("⚠️  ") + SettingsField.TELEGRAM_BOT_TOKEN.warning());
        warning.getStyleClass().addAll(Styles.WARNING, Styles.TEXT_BOLD);
        warning.setWrapText(true);

        return new VBox(8,
                caption,
                note,
                field(SettingsField.TELEGRAM_BOT_TOKEN.title(),
                        secretRow(botToken, botTokenVisible, showBotToken)),
                warning,
                field(SettingsField.LLM_API_KEY.title() + " (for the selected provider)",
                        secretRow(apiKey, apiKeyVisible, showApiKey)),
                applyNote(SettingsField.LLM_API_KEY));
    }

    private Region providerBlock() {
        Label caption = new Label(Messages.t("Model"));
        caption.getStyleClass().add(Styles.TEXT_BOLD);
        Label note = new Label(Messages.t("The provider is global for the whole agent. Each provider has its own key, model "
                + "and endpoint -- switching does not lose the others. A request already in progress finishes "
                + "on the old provider; the next one goes to the selected one."));
        note.getStyleClass().add(Styles.TEXT_SUBTLE);
        note.setWrapText(true);

        provider.getItems().setAll(AppSettings.PROVIDERS);
        provider.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : (AppSettings.PROVIDER_CLAUDE.equals(id) ? "Claude (Anthropic)" : "Ollama (Cloud / local)");
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        provider.setOnAction(e -> switchDraft(provider.getValue()));

        model.setEditable(true);
        model.setPrefWidth(320);
        loadModels.setOnAction(e -> loadModelList());
        HBox modelRow = new HBox(8, model, loadModels);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        endpoint.setPromptText(Messages.t("empty -- the provider's default address"));
        Label endpointHint = new Label(Messages.t("Defaults: Ollama -- https://ollama.com (local daemon -- http://localhost:11434), "
                + "Claude -- https://api.anthropic.com. Fill in for a proxy or a compatible self-hosted endpoint. "
                + "Changing it recreates the provider client."));
        endpointHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        endpointHint.setWrapText(true);
        javafx.scene.control.TitledPane advanced = new javafx.scene.control.TitledPane("Advanced",
                new VBox(6, field(SettingsField.LLM_ENDPOINT.title(), endpoint), endpointHint));
        advanced.setExpanded(false);
        advanced.setAnimated(true);

        return new VBox(8,
                caption,
                note,
                field(SettingsField.LLM_PROVIDER.title(), provider),
                field(SettingsField.LLM_MODEL.title(), modelRow),
                advanced);
    }

    private void switchDraft(String next) {
        if (next == null || next.equals(draftProvider)) {
            return;
        }
        if (draftProvider != null) {
            draftSlots.put(draftProvider, new String[] {text(apiKey), model.getValue() == null ? "" : model.getValue().trim(), endpoint.getText().trim()});
        }
        draftProvider = next;
        String[] slot = draftSlots.computeIfAbsent(next, id -> {
            AppSettings.ProviderSlot saved = settings.slot(id);
            return new String[] {saved.apiKey(), saved.model(), saved.endpoint()};
        });
        apiKey.setText(slot[0]);
        model.getItems().clear();
        model.setValue(slot[1].isEmpty() ? LlmProviders.defaultModel(next) : slot[1]);
        endpoint.setText(slot[2]);
        endpoint.setPromptText(Messages.t("empty -- ") + LlmProviders.defaultEndpoint(next));
    }

    private Region secretRow(PasswordField masked, TextField visible, CheckBox toggle) {
        visible.textProperty().bindBidirectional(masked.textProperty());

        visible.visibleProperty().bind(toggle.selectedProperty());
        visible.managedProperty().bind(toggle.selectedProperty());
        masked.visibleProperty().bind(toggle.selectedProperty().not());
        masked.managedProperty().bind(toggle.selectedProperty().not());

        HBox.setHgrow(masked, Priority.ALWAYS);
        HBox.setHgrow(visible, Priority.ALWAYS);

        HBox row = new HBox(8, masked, visible, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Region liveBlock() {
        Label caption = new Label(Messages.t("Applies immediately"));
        caption.getStyleClass().add(Styles.TEXT_BOLD);

        usernames.setPromptText(Messages.t("QaterAlong, second_user"));
        Label usernamesHint = new Label(Messages.t("Comma-separated. An empty list -- the bot answers no one."));
        usernamesHint.getStyleClass().add(Styles.TEXT_SUBTLE);

        proactiveHints.setText(Messages.t("The agent offers hints on its own"));
        voiceReplies.setText(Messages.t("Voice the replies (Piper) and send them to Telegram as voice messages"));
        liveReplies.setText(Messages.t("Several short messages instead of one long one"));
        Label liveHint = new Label(Messages.t("Enables the mechanism; how exactly to split (length, tone, pauses) is "
                + "set by the active persona's instruction. The voice message is still one per reply."));
        liveHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        liveHint.setWrapText(true);
        scriptsEnabled.setText(Messages.t("Allow the agent to run Python scripts on the computer"));
        Label scriptsHint = new Label(Messages.t("Off -- the model knows nothing about scripts: it answers itself or with "
                + "tools, and when asked to do something on the computer says that actions are disabled. "
                + "Useful if it mistakes ordinary messages for tasks."));
        scriptsHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        scriptsHint.setWrapText(true);
        typingIndicator.setText(Messages.t("Show \"typing...\" in Telegram while the agent prepares a reply"));
        Label typingHint = new Label(Messages.t("Between the question and the first message -- always; between short "
                + "messages -- only if the setting above is on too."));
        typingHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        typingHint.setWrapText(true);
        language.getItems().setAll(com.bebebe.agent.i18n.Language.values());
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(com.bebebe.agent.i18n.Language value) {
                return value == null ? "" : value.title();
            }

            @Override
            public com.bebebe.agent.i18n.Language fromString(String value) {
                return com.bebebe.agent.i18n.Language.from(value);
            }
        });
        Label languageHint = new Label(Messages.t(SettingsField.LANGUAGE.warning()));
        languageHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        languageHint.setWrapText(true);
        voiceInput.setText(Messages.t("Listen to voice messages sent in Telegram"));
        Label voiceInputHint = new Label(Messages.t("The voice message is downloaded, converted by ffmpeg and "
                + "recognised by the same whisper.cpp as push-to-talk -- the [stt] section must be "
                + "filled in. Off -- the bot answers that it does not accept voice."));
        voiceInputHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        voiceInputHint.setWrapText(true);
        Label voiceHint = new Label(Messages.t("The text stays, the voice follows. By default only for voice "
                + "requests; for text ones -- tts.reply_to_text in the config."));
        voiceHint.getStyleClass().add(Styles.TEXT_SUBTLE);
        voiceHint.setWrapText(true);

        return new VBox(8,
                caption,
                field(SettingsField.ALLOWED_USERNAMES.title(), new VBox(4, usernames, usernamesHint)),
                field(SettingsField.PROACTIVE_HINTS.title(), proactiveHints),
                field(SettingsField.VOICE_REPLIES.title(), new VBox(4, voiceReplies, voiceHint)),
                field(SettingsField.VOICE_INPUT.title(), new VBox(4, voiceInput, voiceInputHint)),
                field(SettingsField.LANGUAGE.title(), new VBox(4, language, languageHint)),
                field(SettingsField.LIVE_REPLIES.title(), new VBox(4, liveReplies, liveHint)),
                field(SettingsField.TYPING_INDICATOR.title(), new VBox(4, typingIndicator, typingHint)),
                field(SettingsField.SCRIPTS_ENABLED.title(), new VBox(4, scriptsEnabled, scriptsHint)));
    }

    private Region buttonsRow() {
        save.getStyleClass().add(Styles.ACCENT);
        save.setOnAction(e -> saveAll());
        reset.setOnAction(e -> {
            loadFromSettings();
            setStatus("Changes discarded", false);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, save, reset, spacer, status);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void loadFromSettings() {
        botToken.setText(settings.telegramBotToken());
        draftSlots.clear();
        draftProvider = null;
        provider.setValue(settings.provider());
        switchDraft(settings.provider());
        usernames.setText(String.join(", ", settings.allowedUsernames()));
        proactiveHints.setSelected(settings.proactiveHints());
        voiceReplies.setSelected(settings.voiceReplies());
        liveReplies.setSelected(settings.liveReplies());
        typingIndicator.setSelected(settings.typingIndicator());
        voiceInput.setSelected(settings.voiceInput());
        language.setValue(settings.language());
        scriptsEnabled.setSelected(settings.scriptsEnabled());
    }

    private void saveAll() {
        settings.setTelegramBotToken(text(botToken));

        switchDraft(provider.getValue());
        draftSlots.put(draftProvider, new String[] {text(apiKey), model.getValue() == null ? "" : model.getValue().trim(), endpoint.getText().trim()});
        String target = provider.getValue();
        for (var e : draftSlots.entrySet()) {
            settings.setProvider(e.getKey());
            settings.setApiKey(e.getValue()[0]);
            settings.setModel(e.getValue()[1]);
            settings.setEndpoint(e.getValue()[2]);
        }
        settings.setProvider(target);
        settings.setAllowedUsernames(splitUsernames(usernames.getText()));
        settings.setProactiveHints(proactiveHints.isSelected());
        settings.setVoiceReplies(voiceReplies.isSelected());
        settings.setLiveReplies(liveReplies.isSelected());
        settings.setTypingIndicator(typingIndicator.isSelected());
        settings.setVoiceInput(voiceInput.isSelected());
        settings.setLanguage(language.getValue());
        settings.setScriptsEnabled(scriptsEnabled.isSelected());

        try {
            settings.save();
            setStatus("Saved to " + settings.file().getFileName(), false);
            log.info("Settings saved from the GUI");
        } catch (ConfigException e) {
            log.error("Failed to save settings", e);
            setStatus("Applied but not saved: " + e.getMessage(), true);
        }
    }

    static List<String> splitUsernames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void loadModelList() {
        loadModels.setDisable(true);
        setStatus("Requesting the model list...", false);

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                return llm.listModels();
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            String selected = model.getValue();
            model.getItems().setAll(task.getValue());
            model.setValue(selected);
            setStatus("Models: " + task.getValue().size(), false);
            loadModels.setDisable(false);
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            log.warn("Model list not received", task.getException());
            setStatus("Failed to fetch the list: " + task.getException().getMessage(), true);
            loadModels.setDisable(false);
        }));
        executor.execute(task);
    }

    private static String text(TextInputControl control) {
        return control.getText() == null ? "" : control.getText().trim();
    }

    private static Region field(String label, Region control) {
        Label caption = new Label(label);
        caption.getStyleClass().add(Styles.TEXT_MUTED);
        return new VBox(4, caption, control);
    }

    private static Label applyNote(SettingsField field) {
        Label label = new Label(field.apply() == SettingsField.Apply.LIVE
                ? "Applies immediately, no restart."
                : field.warning());
        label.getStyleClass().add(Styles.TEXT_SUBTLE);
        label.setWrapText(true);
        return label;
    }

    private void setStatus(String message, boolean danger) {
        status.setText(message);
        status.getStyleClass().removeAll(Styles.DANGER, Styles.TEXT_MUTED);
        status.getStyleClass().add(danger ? Styles.DANGER : Styles.TEXT_MUTED);
    }

    public void dispose() {
        settings.removeListener(listener);
        executor.shutdownNow();
    }
}
