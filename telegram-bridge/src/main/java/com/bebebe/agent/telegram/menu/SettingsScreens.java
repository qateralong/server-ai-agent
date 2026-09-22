package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.i18n.Messages;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.config.SettingsField;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardButton;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.TelegramApi;

import java.util.ArrayList;
import java.util.List;

public final class SettingsScreens {

    static final int MAX_MODELS = 12;

    private SettingsScreens() {
    }

    public static MenuScreen root(AppSettings settings) {
        return root(settings, false);
    }

    /**
     * One button per language, the current one marked. A toggle would do for two, but the
     * name of a language has to be readable before you switch to it -- so they are all shown.
     */
    private static List<InlineKeyboardButton> languageRow(com.bebebe.agent.i18n.Language current) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (com.bebebe.agent.i18n.Language language : com.bebebe.agent.i18n.Language.values()) {
            row.add(InlineKeyboardButton.of(
                    (language == current ? "✅ " : "🌐 ") + language.title(),
                    CallbackData.language(language.code()).encode()));
        }
        return row;
    }

    public static MenuScreen root(AppSettings settings, boolean readOnly) {
        String model = settings.model();
        String providerName = AppSettings.PROVIDER_CLAUDE.equals(settings.provider()) ? "Claude" : "Ollama";
        List<String> usernames = settings.allowedUsernames();
        boolean hints = settings.proactiveHints();
        boolean voice = settings.voiceReplies();
        boolean live = settings.liveReplies();
        boolean typing = settings.typingIndicator();
        boolean voiceIn = settings.voiceInput();
        com.bebebe.agent.i18n.Language language = settings.language();
        boolean scripts = settings.scriptsEnabled();

        String text = Messages.t("""
                <b>%s</b>

                🤖 Provider: %s
                🧠 Model: <code>%s</code>
                👤 Access: %s
                💡 Hints: %s
                🔊 Voice replies: %s
                🌐 Language: %s
                🎤 Voice messages from you: %s
                💬 Several short messages: %s
                ⌨️ "Typing..." indicator: %s
                🐍 Scripts: %s

                🔐 Provider API key: %s
                🔐 Bot token: %s

                <i>%s</i>""").formatted(
                MenuSection.SETTINGS.title(),
                providerName,
                TelegramApi.escapeHtml(model.isEmpty() ? "not set" : model),
                usernames.isEmpty() ? "no one (the bot is silent)" : String.valueOf(usernames.size()),
                hints ? "on" : "off",
                voice ? "on" : "off",
                language.title(),
                voiceIn ? "accepted" : "off",
                live ? "on" : "off",
                typing ? "on" : "off",
                scripts ? "allowed" : "off",
                secretBadge(settings, SettingsField.LLM_API_KEY),
                secretBadge(settings, SettingsField.TELEGRAM_BOT_TOKEN),
                readOnly
                        ? "Server mode: settings are read from config.toml at startup and edited "
                          + "only in the file over SSH; after editing -- systemctl restart. Not changeable from the chat."
                        : "Secrets are edited only in the application window -- they are "
                          + "neither sent to nor shown in the chat.");

        if (readOnly) {
            return new MenuScreen(MenuSection.SETTINGS, text, InlineKeyboardMarkup.of(List.of(
                    List.of(InlineKeyboardButton.of("💾 Backup (no secrets)", CallbackData.backup().encode())),
                    navigationRow())));
        }

        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(InlineKeyboardButton.of(
                        AppSettings.PROVIDER_CLAUDE.equals(settings.provider())
                                ? "🤖 Switch to Ollama" : "🤖 Switch to Claude",
                        CallbackData.provider(AppSettings.PROVIDER_CLAUDE.equals(settings.provider())
                                ? AppSettings.PROVIDER_OLLAMA : AppSettings.PROVIDER_CLAUDE).encode())),
                List.of(InlineKeyboardButton.of("🧠 Model", CallbackData.modelList().encode())),
                List.of(InlineKeyboardButton.of("👤 Access", CallbackData.userList().encode())),
                List.of(InlineKeyboardButton.of(
                        hints ? "💡 Disable hints" : "💡 Enable hints",
                        CallbackData.hints(!hints).encode())),
                List.of(InlineKeyboardButton.of(
                        voice ? "🔊 Disable voice replies" : "🔊 Enable voice replies",
                        CallbackData.voiceReplies(!voice).encode())),
                languageRow(language),
                List.of(InlineKeyboardButton.of(
                        voiceIn ? "🎤 Do not accept voice messages" : "🎤 Accept voice messages",
                        CallbackData.voiceInput(!voiceIn).encode())),
                List.of(InlineKeyboardButton.of(
                        live ? "💬 One message instead of several" : "💬 Several short messages",
                        CallbackData.liveReplies(!live).encode())),
                List.of(InlineKeyboardButton.of(
                        typing ? "⌨️ Disable \"typing...\"" : "⌨️ Enable \"typing...\"",
                        CallbackData.typingIndicator(!typing).encode())),
                List.of(InlineKeyboardButton.of(
                        scripts ? "🐍 Disable scripts" : "🐍 Allow scripts",
                        CallbackData.scriptsEnabled(!scripts).encode())),
                List.of(InlineKeyboardButton.of("💾 Backup (no secrets)", CallbackData.backup().encode())),
                navigationRow());

        return new MenuScreen(MenuSection.SETTINGS, text, InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen models(AppSettings settings, List<String> models) {
        String current = settings.model();

        if (models.isEmpty()) {
            return new MenuScreen(MenuSection.SETTINGS, Messages.t("""
                    <b>🧠 Model</b>

                    Current: <code>%s</code>

                    Failed to fetch the model list from the provider -- \
                    check the key and network availability.""").formatted(TelegramApi.escapeHtml(current)),
                    InlineKeyboardMarkup.of(List.of(backToSettingsRow())));
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            String mark = model.equals(current) ? "✅ " : "";
            rows.add(List.of(InlineKeyboardButton.of(mark + model, CallbackData.modelPick(i).encode())));
        }
        rows.add(backToSettingsRow());

        String text = Messages.t("""
                <b>🧠 Model</b>

                Current: <code>%s</code>

                Choose a model -- it applies immediately, without restart.""").formatted(
                TelegramApi.escapeHtml(current.isEmpty() ? "not set" : current));

        return new MenuScreen(MenuSection.SETTINGS, text, InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen usernames(AppSettings settings) {
        List<String> usernames = settings.allowedUsernames();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < usernames.size(); i++) {
            rows.add(List.of(InlineKeyboardButton.of(
                    "🗑 @" + usernames.get(i), CallbackData.userRemove(i).encode())));
        }
        rows.add(List.of(InlineKeyboardButton.of("➕ Add", CallbackData.userAdd().encode())));
        rows.add(backToSettingsRow());

        String list = usernames.isEmpty()
                ? "The list is empty -- the bot answers no one."
                : usernames.stream().map(name -> "• @" + TelegramApi.escapeHtml(name))
                        .reduce((a, b) -> a + "\n" + b).orElse("");

        String text = Messages.t("""
                <b>👤 Access</b>

                %s

                The bin button removes a username. \
                Changes take effect from the next message.""").formatted(list);

        return new MenuScreen(MenuSection.SETTINGS, text, InlineKeyboardMarkup.of(rows));
    }

    public static MenuScreen awaitingUsername() {
        return new MenuScreen(MenuSection.SETTINGS, Messages.t("""
                <b>👤 Add username</b>

                Send the username in the next message -- with or without @, any case.

                Cancel: /cancel"""),
                InlineKeyboardMarkup.of(List.of(backToSettingsRow())));
    }

    private static String secretBadge(AppSettings settings, SettingsField field) {
        return settings.value(field).toString().isEmpty() ? "not set" : "set";
    }

    private static List<InlineKeyboardButton> backToSettingsRow() {
        return List.of(
                InlineKeyboardButton.of("⬅️ To settings", CallbackData.section(MenuSection.SETTINGS).encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }

    private static List<InlineKeyboardButton> navigationRow() {
        return List.of(
                InlineKeyboardButton.of("⬅️ Back", CallbackData.root().encode()),
                InlineKeyboardButton.of("✖️ Close", CallbackData.close().encode()));
    }
}
