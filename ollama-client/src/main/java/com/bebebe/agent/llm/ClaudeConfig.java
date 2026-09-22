package com.bebebe.agent.llm;

import com.bebebe.agent.config.ConfigSection;

import java.time.Duration;

public record ClaudeConfig(int maxTokens, Duration timeout, Double temperature) {

    public static final String SECTION = "claude";
    public static final String DEFAULT_ENDPOINT = "https://api.anthropic.com";

    public static final String DEFAULT_MODEL = "claude-opus-5";

    public static ClaudeConfig from(ConfigSection section) {
        int maxTokens = section.integer("max_tokens", 4096);
        if (maxTokens < 256) {
            throw new IllegalArgumentException("claude.max_tokens must be >= 256");
        }
        return new ClaudeConfig(maxTokens,
                section.seconds("timeout_seconds", Duration.ofSeconds(120)),
                section.doubleValue("temperature").orElse(null));
    }

    public static ClaudeConfig defaults() {
        return new ClaudeConfig(4096, Duration.ofSeconds(120), null);
    }
}
