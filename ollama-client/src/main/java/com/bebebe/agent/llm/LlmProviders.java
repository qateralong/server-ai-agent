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

    /**
     * Embeddings, always through Ollama's slot whatever the chat provider is.
     *
     * <p>Anthropic has no embedding API, so a Claude-driven agent that wants recall by meaning
     * still reaches an Ollama endpoint for it -- local or cloud, whichever is configured in
     * {@code [llm.ollama]}. Without a model name in {@code [memory] embedding_model} the result
     * reports itself as not ready and recall stays lexical.
     */
    public EmbeddingProvider embeddings(String model) {
        if (model == null || model.isBlank()) {
            return new OllamaEmbeddings(null, "");
        }
        AppSettings.ProviderSlot slot = settings.slot(AppSettings.PROVIDER_OLLAMA);
        var section = config.section(OllamaConfig.SECTION);
        OllamaConfig ollamaConfig = new OllamaConfig(
                slot.endpoint().isBlank() ? OllamaConfig.DEFAULT_BASE_URL : slot.endpoint(),
                slot.apiKey(),
                model.strip(),
                section.seconds("timeout_seconds", OllamaConfig.DEFAULT_TIMEOUT),
                null, null);
        return new OllamaEmbeddings(new com.bebebe.agent.ollama.OllamaClient(ollamaConfig), model.strip());
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
