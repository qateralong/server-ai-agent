package com.bebebe.agent.script.library;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ScriptEntry(
        long id,
        String name,
        String description,
        List<String> tags,
        Path path,
        int version,
        long rootId,
        int successCount,
        int failureCount,
        boolean requiresConfirmation,
        Instant createdAt,
        Instant lastUsedAt
) {

    public ScriptEntry {
        tags = tags == null ? List.of() : List.copyOf(tags);
        name = name == null ? "" : name.strip();
        description = description == null ? "" : description.strip();
    }

    public int totalRuns() {
        return successCount + failureCount;
    }

    public double successRate() {
        int total = totalRuns();
        return total == 0 ? 0.5 : (double) successCount / total;
    }

    public String displayName() {
        return version > 1 ? name + " v" + version : name;
    }

    public String describeForModel() {
        StringBuilder sb = new StringBuilder("[").append(id).append("] ").append(displayName());
        if (!description.isEmpty()) {
            sb.append(" — ").append(description);
        }
        if (!tags.isEmpty()) {
            sb.append(" (tags: ").append(String.join(", ", tags)).append(')');
        }
        if (totalRuns() > 0) {
            sb.append(" [success ").append(successCount).append('/').append(totalRuns()).append(']');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ScriptEntry[" + id + " " + displayName() + "]";
    }
}
