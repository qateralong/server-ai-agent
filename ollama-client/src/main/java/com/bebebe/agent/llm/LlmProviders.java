package com.bebebe.agent.llm;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.ollama.OllamaStats;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LlmProviders {

    private final AppConfig config;
    private final AppSettings settings;
    private final Map<String, OllamaStats> stats = new ConcurrentHashMap<>();

    public LlmProviders(AppConfig config, AppSettings settings) {
        this.config = config;
        this.settings = settings;
    }

    public OllamaStats statsOf(String providerId) {
        return stats.computeIfAbsent(providerId, id -> new OllamaStats());
    }

    public LlmProvider create(String providerId) {
        String id = AppSettings.normalizeProvider(providerId);
        AppSettings.ProviderSlot slot = settings.slot(id);
        return switch (id) {
            case AppSettings.PROVIDER_CLAUDE -> new ClaudeProvider(
                    ClaudeConfig.from(config.section(ClaudeConfig.SECTION)),
                    slot.apiKey(), slot.model(), slot.endpoint(), statsOf(id));
            default -> {
                var section = config.section(OllamaConfig.SECTION);
                OllamaConfig ollamaConfig = new OllamaConfig(
                        slot.endpoint().isBlank() ? OllamaConfig.DEFAULT_BASE_URL : slot.endpoint(),
                        slot.apiKey(),
                        slot.model().isBlank() ? OllamaConfig.DEFAULT_MODEL : slot.model(),
                        section.seconds("timeout_seconds", OllamaConfig.DEFAULT_TIMEOUT),
                        section.doubleValue("temperature").orElse(null),
                        section.integer("num_ctx").orElse(null));
                yield new OllamaProvider(new com.bebebe.agent.ollama.OllamaClient(ollamaConfig, statsOf(id)));
            }
        };
    }

    public SwitchableProvider switchable() {
        return new SwitchableProvider(create(settings.provider()), this::create);
    }

    public static String defaultEndpoint(String providerId) {
        return AppSettings.PROVIDER_CLAUDE.equals(AppSettings.normalizeProvider(providerId))
                ? ClaudeConfig.DEFAULT_ENDPOINT : OllamaConfig.DEFAULT_BASE_URL;
    }

    public static String defaultModel(String providerId) {
        return AppSettings.PROVIDER_CLAUDE.equals(AppSettings.normalizeProvider(providerId))
                ? ClaudeConfig.DEFAULT_MODEL : OllamaConfig.DEFAULT_MODEL;
    }

    public static Duration fallbackTimeout() {
        return Duration.ofSeconds(120);
    }
}
