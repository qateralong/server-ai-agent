package com.bebebe.agent.memory;

import java.time.Instant;

public record Persona(long id, String name, String prompt, Instant createdAt, boolean active) {

    public String displayLine() {
        return (active ? "✅ " : "") + name;
    }
}
