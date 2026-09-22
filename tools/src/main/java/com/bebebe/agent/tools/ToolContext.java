package com.bebebe.agent.tools;

import java.util.Map;

public record ToolContext(LlmCaller llm, String question) {

    @FunctionalInterface
    public interface LlmCaller {

        String ask(String system, String user, Map<String, Object> schema);
    }

    public static class BudgetExhausted extends RuntimeException {
        public BudgetExhausted(String message) {
            super(message);
        }
    }
}
