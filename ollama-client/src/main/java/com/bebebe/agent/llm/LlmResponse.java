package com.bebebe.agent.llm;

public record LlmResponse(String text, Integer inputTokens, Integer outputTokens, Long durationNanos, String model) {

    public LlmResponse {
        text = text == null ? "" : text;
    }

    public Double tokensPerSecond() {
        if (outputTokens == null || durationNanos == null || durationNanos == 0) {
            return null;
        }
        return outputTokens * 1_000_000_000.0 / durationNanos;
    }
}
