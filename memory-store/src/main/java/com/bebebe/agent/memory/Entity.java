package com.bebebe.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record Entity(
        long id,
        String canonicalName,
        List<String> aliases,
        String relation,
        String notes,
        Instant createdAt
) {

    public Entity {
        canonicalName = canonicalName == null ? "" : canonicalName.strip();
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        relation = relation == null ? "" : relation.strip();
        notes = notes == null ? "" : notes.strip();
    }

    public List<String> allNamesLower() {
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(canonicalName), aliases.stream())
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public String describeForModel() {
        StringBuilder sb = new StringBuilder("[").append(id).append("] ").append(canonicalName);
        if (!relation.isEmpty() || !aliases.isEmpty()) {
            sb.append(" (");
            if (!relation.isEmpty()) {
                sb.append(relation);
            }
            if (!aliases.isEmpty()) {
                if (!relation.isEmpty()) {
                    sb.append("; ");
                }
                sb.append("also: ").append(String.join(", ", aliases));
            }
            sb.append(')');
        }
        return sb.toString();
    }

    public String displayName() {
        return relation.isEmpty() ? canonicalName : canonicalName + " · " + relation;
    }
}
