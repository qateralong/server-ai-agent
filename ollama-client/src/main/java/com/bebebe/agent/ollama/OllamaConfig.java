package com.bebebe.agent.ollama;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.ConfigException;
import com.bebebe.agent.config.ConfigPaths;
import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public record OllamaConfig(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        Double temperature,
        Integer numCtx
) {

    public static final String DEFAULT_BASE_URL = "https://ollama.com";

    public static final String DEFAULT_MODEL = "gpt-oss:120b";

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    public OllamaConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("ollama.base_url is not set");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("ollama.model is not set");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("ollama.timeout_seconds must be > 0");
        }

        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        apiKey = apiKey == null ? "" : apiKey.trim();

        if (!apiKey.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw new IllegalArgumentException(
                    "ollama.api_key contains invalid characters: only printable ASCII is expected "
                            + "(most likely Cyrillic, a space or a line break got into the config)");
        }
    }

    public static OllamaConfig localDefault() {
        return new OllamaConfig("http://localhost:11434", "", "llama3.2", DEFAULT_TIMEOUT, null, null);
    }

    public static final String SECTION = "ollama";

    public static Path defaultPath() {
        return ConfigPaths.defaultPath();
    }

    public static OllamaConfig load() {
        return load(ConfigPaths.defaultPath());
    }

    public static OllamaConfig load(Path tomlFile) {
        try {
            return from(AppConfig.load(tomlFile).section(SECTION));
        } catch (ConfigException e) {
            throw new OllamaException(e.getMessage(), e);
        }
    }

    public static OllamaConfig from(ConfigSection section) {
        if (!section.has("base_url") && !section.has("api_key") && !section.has("model")) {
            throw new OllamaException("The config has no [ollama] section (or it is empty)");
        }
        try {
            return new OllamaConfig(
                    section.string("base_url", DEFAULT_BASE_URL),
                    section.string("api_key", ""),
                    section.string("model", DEFAULT_MODEL),
                    section.seconds("timeout_seconds", DEFAULT_TIMEOUT),
                    section.doubleValue("temperature").orElse(null),
                    section.integer("num_ctx").orElse(null));
        } catch (ConfigException | IllegalArgumentException e) {
            throw new OllamaException("Section [ollama] is invalid: " + e.getMessage(), e);
        }
    }

    public boolean requiresApiKey() {
        return baseUrl.contains("ollama.com");
    }

    public Optional<String> bearerToken() {
        return apiKey.isBlank() ? Optional.empty() : Optional.of(apiKey);
    }

    @Override
    public String toString() {
        return "OllamaConfig[baseUrl=%s, model=%s, timeout=%ds, apiKey=%s]"
                .formatted(baseUrl, model, timeout.toSeconds(), apiKey.isBlank() ? "<empty>" : "<set>");
    }
}
