package com.bebebe.agent.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Map<String, Object> options,
        Object format,
        Object think,
        @JsonProperty("keep_alive") String keepAlive
) {

    public static Builder builder(String model) {
        return new Builder(model);
    }

    public static final class Builder {
        private final String model;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final Map<String, Object> options = new LinkedHashMap<>();
        private Object format;
        private Object think;
        private String keepAlive;

        private Builder(String model) {
            this.model = model;
        }

        public Builder message(ChatMessage message) {
            messages.add(message);
            return this;
        }

        public Builder messages(List<ChatMessage> list) {
            messages.addAll(list);
            return this;
        }

        public Builder system(String text) {
            return message(ChatMessage.system(text));
        }

        public Builder user(String text) {
            return message(ChatMessage.user(text));
        }

        public Builder user(String text, java.util.List<String> base64Images) {
            return message(ChatMessage.user(text, base64Images));
        }

        public Builder temperature(Double value) {
            return option("temperature", value);
        }

        public Builder numCtx(Integer value) {
            return option("num_ctx", value);
        }

        public Builder option(String key, Object value) {
            if (value != null) {
                options.put(key, value);
            }
            return this;
        }

        public Builder format(Object value) {
            this.format = value;
            return this;
        }

        public Builder think(Object value) {
            this.think = value;
            return this;
        }

        public Builder keepAlive(String value) {
            this.keepAlive = value;
            return this;
        }

        public ChatRequest build() {
            if (messages.isEmpty()) {
                throw new IllegalStateException("The request has no messages");
            }
            return new ChatRequest(
                    model,
                    List.copyOf(messages),
                    options.isEmpty() ? null : Map.copyOf(options),
                    format,
                    think,
                    keepAlive);
        }
    }
}
