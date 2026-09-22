package com.bebebe.agent.config;

public enum SettingsField {

    LLM_PROVIDER("llm", "provider", Apply.LIVE,
            "Provider", false),

    LLM_API_KEY("llm", "api_key", Apply.LIVE,
            "API key", true),

    LLM_MODEL("llm", "model", Apply.LIVE,
            "Model", false),

    LLM_ENDPOINT("llm", "endpoint", Apply.RECONNECT,
            "Endpoint URL", false),

    TELEGRAM_BOT_TOKEN("telegram", "bot_token", Apply.RECONNECT,
            "Telegram bot token", true),

    ALLOWED_USERNAMES("telegram", "allowed_usernames", Apply.LIVE,
            "Allowed usernames", false),

    PROACTIVE_HINTS("agent", "proactive_hints", Apply.LIVE,
            "Proactive hints", false),

    VOICE_REPLIES("tts", "voice_replies", Apply.LIVE,
            "Voice replies", false),

    LIVE_REPLIES("agent", "live_replies", Apply.LIVE,
            "Several short messages", false),

    TYPING_INDICATOR("telegram", "typing_indicator", Apply.LIVE,
            "\"Typing...\" indicator", false),

    SCRIPTS_ENABLED("agent", "scripts_enabled", Apply.LIVE,
            "Scripts (actions on the computer)", false);

    public enum Apply {

        LIVE,

        RECONNECT
    }

    private final String section;
    private final String key;
    private final Apply apply;
    private final String title;
    private final boolean secret;

    SettingsField(String section, String key, Apply apply, String title, boolean secret) {
        this.section = section;
        this.key = key;
        this.apply = apply;
        this.title = title;
        this.secret = secret;
    }

    public String section() {
        return section;
    }

    public String key() {
        return key;
    }

    public String path() {
        return section + "." + key;
    }

    public Apply apply() {
        return apply;
    }

    public String title() {
        return title;
    }

    public boolean isSecret() {
        return secret;
    }

    public String warning() {
        return apply == Apply.RECONNECT
                ? "Changing the token drops the current Telegram session: the bridge will reconnect."
                : "";
    }
}
