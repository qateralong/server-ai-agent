package com.bebebe.agent.core;

import com.bebebe.agent.script.library.ScriptEntry;

public sealed interface AgentReply {

    record Text(String text, java.util.List<String> parts) implements AgentReply {

        public Text {
            java.util.List<String> clean = parts == null ? java.util.List.of() : parts.stream()
                    .filter(p -> p != null && !p.isBlank()).map(String::strip).toList();
            if (clean.isEmpty()) {
                clean = text == null || text.isBlank() ? java.util.List.of() : java.util.List.of(text.strip());
            }
            parts = clean;
            text = text == null || text.isBlank() ? String.join("\n\n", clean) : text.strip();
        }

        public Text(String text) {
            this(text, null);
        }

        public boolean isMultipart() {
            return parts.size() > 1;
        }
    }

    record NeedsConfirmation(String token, ScriptEntry script, String description) implements AgentReply {
    }

    record EntityQuestion(String token, String mention, com.bebebe.agent.memory.Entity candidate, int heldFacts)
            implements AgentReply {
    }

    record Silence() implements AgentReply {
    }

    static AgentReply text(String value) {
        return new Text(value);
    }

    static AgentReply parts(java.util.List<String> parts) {
        return new Text(null, parts);
    }

    static AgentReply silence() {
        return new Silence();
    }

    default String asPlainText() {
        return switch (this) {
            case Text t -> t.text();
            case NeedsConfirmation c -> "Нужно подтверждение на запуск «"
                    + c.script().displayName() + "»: " + c.description();
            case EntityQuestion q -> "«" + q.mention() + "» — это " + q.candidate().canonicalName()
                    + " или другой человек?";
            case Silence ignored -> "";
        };
    }
}
