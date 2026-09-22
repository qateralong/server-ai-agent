package com.bebebe.agent.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Fact(
        long id,
        String text,
        FactCategory category,
        LocalDate factDate,
        Long sourceMessageId,
        List<Long> entityIds,
        Instant createdAt
) {

    public Fact {
        text = text == null ? "" : text.strip();
        category = category == null ? FactCategory.EVENT : category;
        entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
    }

    public String describeForModel() {
        StringBuilder sb = new StringBuilder("• ");
        if (factDate != null) {
            sb.append(factDate).append(": ");
        }
        sb.append(text);
        if (category == FactCategory.PROCEDURE) {
            sb.append(" [procedure]");
        }
        return sb.toString();
    }
}
