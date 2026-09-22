package com.bebebe.agent.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        String role,
        String content,
        String thinking,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls
) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(Function function) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Function(String name, java.util.Map<String, Object> arguments) {
        }
    }
}
