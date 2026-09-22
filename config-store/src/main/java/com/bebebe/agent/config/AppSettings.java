package com.bebebe.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);

    private final Path file;
    private final List<Consumer<SettingsField>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private String provider;
    private final Map<String, ProviderSlot> slots = new java.util.LinkedHashMap<>();
    private String telegramBotToken;
    private List<String> allowedUsernames;
    private boolean proactiveHints;
    private boolean voiceReplies;
    private boolean liveReplies;
    private boolean typingIndicator;
    private boolean voiceInput;
    private boolean scriptsEnabled;

    public static final class ProviderSlot {
        String apiKey = "";
        String model = "";
        String endpoint = "";

        public String apiKey() {
            return apiKey;
        }

        public String model() {
            return model;
        }

        public String endpoint() {
            return endpoint;
        }
    }

    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_CLAUDE = "claude";
    public static final List<String> PROVIDERS = List.of(PROVIDER_OLLAMA, PROVIDER_CLAUDE);

    private AppSettings(Path file,
                        String provider,
                        Map<String, ProviderSlot> slots,
                        String telegramBotToken,
                        List<String> allowedUsernames,
                        boolean proactiveHints,
                        boolean voiceReplies,
                        boolean liveReplies,
                        boolean typingIndicator,
                        boolean voiceInput,
                        boolean scriptsEnabled) {
        this.file = file;
        this.provider = normalizeProvider(provider);
        for (String id : PROVIDERS) {
            this.slots.put(id, slots.getOrDefault(id, new ProviderSlot()));
        }
        this.telegramBotToken = telegramBotToken;
        this.allowedUsernames = List.copyOf(allowedUsernames);
        this.proactiveHints = proactiveHints;
        this.voiceReplies = voiceReplies;
        this.liveReplies = liveReplies;
        this.typingIndicator = typingIndicator;
        this.voiceInput = voiceInput;
        this.scriptsEnabled = scriptsEnabled;
    }

    public static AppSettings from(AppConfig config) {
        Map<String, ProviderSlot> slots = new java.util.LinkedHashMap<>();
        for (String id : PROVIDERS) {
            ConfigSection section = config.section("llm." + id);
            ProviderSlot slot = new ProviderSlot();
            slot.apiKey = section.string("api_key", "");
            slot.model = section.string("model", "");
            slot.endpoint = section.string("endpoint", "");
            slots.put(id, slot);
        }
        ProviderSlot ollama = slots.get(PROVIDER_OLLAMA);
        ConfigSection legacy = config.section("ollama");
        if (ollama.apiKey.isEmpty()) {
            ollama.apiKey = legacy.string("api_key", "");
        }
        if (ollama.model.isEmpty()) {
            ollama.model = legacy.string("model", "");
        }
        if (ollama.endpoint.isEmpty()) {
            String base = legacy.string("base_url", "");
            ollama.endpoint = base.equals("https://ollama.com") ? "" : base;
        }
        return new AppSettings(
                config.path(),
                config.section("llm").string("provider", PROVIDER_OLLAMA),
                slots,
                config.section("telegram").string("bot_token", ""),
                config.section("telegram").stringList("allowed_usernames"),
                config.section("agent").bool("proactive_hints", false),
                config.section("tts").bool("voice_replies", false),
                config.section("agent").bool("live_replies", false),
                config.section("telegram").bool("typing_indicator", true),
                config.section("telegram").bool("voice_input", true),
                config.section("agent").bool("scripts_enabled", true));
    }

    public Path file() {
        return file;
    }

    public String provider() {
        synchronized (lock) {
            return provider;
        }
    }

    public String apiKey() {
        synchronized (lock) {
            return slots.get(provider).apiKey;
        }
    }

    public String model() {
        synchronized (lock) {
            return slots.get(provider).model;
        }
    }

    public String endpoint() {
        synchronized (lock) {
            return slots.get(provider).endpoint;
        }
    }

    public ProviderSlot slot(String providerId) {
        synchronized (lock) {
            ProviderSlot s = slots.get(normalizeProvider(providerId));
            ProviderSlot copy = new ProviderSlot();
            copy.apiKey = s.apiKey;
            copy.model = s.model;
            copy.endpoint = s.endpoint;
            return copy;
        }
    }

    public static String normalizeProvider(String raw) {
        String id = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return PROVIDERS.contains(id) ? id : PROVIDER_OLLAMA;
    }

    public String telegramBotToken() {
        synchronized (lock) {
            return telegramBotToken;
        }
    }

    public List<String> allowedUsernames() {
        synchronized (lock) {
            return allowedUsernames;
        }
    }

    public boolean proactiveHints() {
        synchronized (lock) {
            return proactiveHints;
        }
    }

    public void setProvider(String value) {
        String normalized = normalizeProvider(value);
        synchronized (lock) {
            if (provider.equals(normalized)) {
                return;
            }
            provider = normalized;
        }
        fire(SettingsField.LLM_PROVIDER);
    }

    public void setApiKey(String value) {
        String normalized = value == null ? "" : value.trim();
        synchronized (lock) {
            if (slots.get(provider).apiKey.equals(normalized)) {
                return;
            }
            slots.get(provider).apiKey = normalized;
        }
        fire(SettingsField.LLM_API_KEY);
    }

    public void setModel(String value) {
        String normalized = value == null ? "" : value.trim();
        synchronized (lock) {
            if (slots.get(provider).model.equals(normalized)) {
                return;
            }
            slots.get(provider).model = normalized;
        }
        fire(SettingsField.LLM_MODEL);
    }

    public void setEndpoint(String value) {
        String normalized = value == null ? "" : value.trim();
        synchronized (lock) {
            if (slots.get(provider).endpoint.equals(normalized)) {
                return;
            }
            slots.get(provider).endpoint = normalized;
        }
        fire(SettingsField.LLM_ENDPOINT);
    }

    public void setTelegramBotToken(String value) {
        String normalized = value == null ? "" : value.trim();
        synchronized (lock) {
            if (telegramBotToken.equals(normalized)) {
                return;
            }
            telegramBotToken = normalized;
        }
        fire(SettingsField.TELEGRAM_BOT_TOKEN);
    }

    public void setAllowedUsernames(List<String> values) {
        List<String> normalized = normalizeUsernames(values);
        synchronized (lock) {
            if (allowedUsernames.equals(normalized)) {
                return;
            }
            allowedUsernames = normalized;
        }
        fire(SettingsField.ALLOWED_USERNAMES);
    }

    public void addAllowedUsername(String username) {
        List<String> updated = new ArrayList<>(allowedUsernames());
        updated.add(username);
        setAllowedUsernames(updated);
    }

    public void removeAllowedUsername(String username) {
        List<String> updated = new ArrayList<>(allowedUsernames());
        updated.remove(normalizeUsername(username));
        setAllowedUsernames(updated);
    }

    public void setProactiveHints(boolean value) {
        synchronized (lock) {
            if (proactiveHints == value) {
                return;
            }
            proactiveHints = value;
        }
        fire(SettingsField.PROACTIVE_HINTS);
    }

    public boolean voiceReplies() {
        synchronized (lock) {
            return voiceReplies;
        }
    }

    public void setVoiceReplies(boolean value) {
        synchronized (lock) {
            if (voiceReplies == value) {
                return;
            }
            voiceReplies = value;
        }
        fire(SettingsField.VOICE_REPLIES);
    }

    public boolean liveReplies() {
        synchronized (lock) {
            return liveReplies;
        }
    }

    public void setLiveReplies(boolean value) {
        synchronized (lock) {
            if (liveReplies == value) {
                return;
            }
            liveReplies = value;
        }
        fire(SettingsField.LIVE_REPLIES);
    }

    public boolean typingIndicator() {
        synchronized (lock) {
            return typingIndicator;
        }
    }

    public void setTypingIndicator(boolean value) {
        synchronized (lock) {
            if (typingIndicator == value) {
                return;
            }
            typingIndicator = value;
        }
        fire(SettingsField.TYPING_INDICATOR);
    }

    public boolean voiceInput() {
        synchronized (lock) {
            return voiceInput;
        }
    }

    public void setVoiceInput(boolean value) {
        synchronized (lock) {
            if (voiceInput == value) {
                return;
            }
            voiceInput = value;
        }
        fire(SettingsField.VOICE_INPUT);
    }

    public boolean scriptsEnabled() {
        synchronized (lock) {
            return scriptsEnabled;
        }
    }

    public void setScriptsEnabled(boolean value) {
        synchronized (lock) {
            if (scriptsEnabled == value) {
                return;
            }
            scriptsEnabled = value;
        }
        fire(SettingsField.SCRIPTS_ENABLED);
    }

    public void save() {
        Map<String, Object> values = new LinkedHashMap<>();
        synchronized (lock) {
            values.put(SettingsField.LLM_PROVIDER.path(), provider);

            for (Map.Entry<String, ProviderSlot> e : slots.entrySet()) {
                values.put("llm." + e.getKey() + ".api_key", e.getValue().apiKey);
                values.put("llm." + e.getKey() + ".model", e.getValue().model);
                values.put("llm." + e.getKey() + ".endpoint", e.getValue().endpoint);
            }
            values.put(SettingsField.TELEGRAM_BOT_TOKEN.path(), telegramBotToken);
            values.put(SettingsField.ALLOWED_USERNAMES.path(), allowedUsernames);
            values.put(SettingsField.PROACTIVE_HINTS.path(), proactiveHints);
            values.put(SettingsField.VOICE_REPLIES.path(), voiceReplies);
            values.put(SettingsField.LIVE_REPLIES.path(), liveReplies);
            values.put(SettingsField.TYPING_INDICATOR.path(), typingIndicator);
            values.put(SettingsField.VOICE_INPUT.path(), voiceInput);
            values.put(SettingsField.SCRIPTS_ENABLED.path(), scriptsEnabled);
        }
        ConfigFileWriter.update(file, values);
    }

    public void addListener(Consumer<SettingsField> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<SettingsField> listener) {
        listeners.remove(listener);
    }

    private void fire(SettingsField field) {
        log.info("Setting changed: {} = {}", field.path(), describe(field));
        for (Consumer<SettingsField> listener : listeners) {
            try {
                listener.accept(field);
            } catch (RuntimeException e) {

                log.warn("Settings subscriber threw an exception on {}", field.path(), e);
            }
        }
    }

    public String describe(SettingsField field) {
        if (field.isSecret()) {
            return value(field).toString().isEmpty() ? "<empty>" : "<set>";
        }
        return String.valueOf(value(field));
    }

    public Object value(SettingsField field) {
        return switch (field) {
            case LLM_PROVIDER -> provider();
            case LLM_API_KEY -> apiKey();
            case LLM_MODEL -> model();
            case LLM_ENDPOINT -> endpoint();
            case TELEGRAM_BOT_TOKEN -> telegramBotToken();
            case ALLOWED_USERNAMES -> allowedUsernames();
            case PROACTIVE_HINTS -> proactiveHints();
            case VOICE_REPLIES -> voiceReplies();
            case LIVE_REPLIES -> liveReplies();
            case TYPING_INDICATOR -> typingIndicator();
            case VOICE_INPUT -> voiceInput();
            case SCRIPTS_ENABLED -> scriptsEnabled();
        };
    }

    private static List<String> normalizeUsernames(List<String> values) {

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalizeUsername(value);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        return List.copyOf(unique);
    }

    public static String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        String trimmed = username.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "AppSettings[provider=%s, model=%s, endpoint=%s, usernames=%d, hints=%s, voice=%s, apiKey=%s, botToken=%s]"
                .formatted(provider(), model(), endpoint().isEmpty() ? "<default>" : endpoint(),
                        allowedUsernames().size(), proactiveHints(), voiceReplies(),
                        describe(SettingsField.LLM_API_KEY), describe(SettingsField.TELEGRAM_BOT_TOKEN));
    }
}
