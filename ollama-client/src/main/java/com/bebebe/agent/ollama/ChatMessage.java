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
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,

        /** Base64 images, as Ollama's chat API takes them: an array on the message itself. */
        List<String> images
) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content, null, null, null);
    }

    public static ChatMessage user(String content, List<String> base64Images) {
        return new ChatMessage(ROLE_USER, content, null, null,
                base64Images == null || base64Images.isEmpty() ? null : List.copyOf(base64Images));
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(Function function) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Function(String name, java.util.Map<String, Object> arguments) {
        }
    }
}
