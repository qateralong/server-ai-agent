package com.bebebe.agent.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String model,
        @JsonProperty("created_at") String createdAt,
        ChatMessage message,
        boolean done,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("total_duration") Long totalDuration,
        @JsonProperty("load_duration") Long loadDuration,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount,
        @JsonProperty("prompt_eval_duration") Long promptEvalDuration,
        @JsonProperty("eval_count") Integer evalCount,
        @JsonProperty("eval_duration") Long evalDuration
) {

    public String text() {
        return message == null || message.content() == null ? "" : message.content();
    }

    public Double tokensPerSecond() {
        if (evalCount == null || evalDuration == null || evalDuration == 0) {
            return null;
        }
        return evalCount / (evalDuration / 1_000_000_000.0);
    }
}
