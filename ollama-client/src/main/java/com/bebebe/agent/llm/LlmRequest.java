package com.bebebe.agent.llm;

import java.util.List;
import java.util.Map;

public record LlmRequest(String system, List<LlmMessage> history, String user, Double temperature,
                         Map<String, Object> schema) {

    public LlmRequest {
        system = system == null ? "" : system;
        history = history == null ? List.of() : List.copyOf(history);
        user = user == null ? "" : user;
    }

    public boolean structured() {
        return schema != null && !schema.isEmpty();
    }

    public record LlmMessage(String role, String text) {

        public static LlmMessage user(String text) {
            return new LlmMessage("user", text);
        }

        public static LlmMessage assistant(String text) {
            return new LlmMessage("assistant", text);
        }
    }
}
