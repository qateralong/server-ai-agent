package com.bebebe.agent.tools.reminder;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ManageRemindersTool implements Tool {

    public static final String NAME = "manage_reminders";

    private static final Logger log = LoggerFactory.getLogger(ManageRemindersTool.class);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.ENGLISH);

    public interface Link {

        boolean cancel(long jobId);

        boolean reschedule(long jobId, Instant fireAt);
    }

    private final Link link;
    private final Clock clock;

    public ManageRemindersTool(Link link, Clock clock) {
        this.link = link;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Cancel or reschedule an existing reminder. Take its id from the \"Active reminders\" "
                + "list in the system prompt; if there is no matching one, say so, do not invent. "
                + "\"Move it an hour later\" -- compute the new moment from the reminder time, not from now.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", Map.of("type", "string", "enum", List.of("cancel", "reschedule"),
                "description", "cancel -- cancel, reschedule -- move"));
        p.put("job_id", Map.of("type", "integer", "description", "reminder id from the system prompt"));
        p.put("fire_at", Map.of("type", "string",
                "description", "new moment in ISO-8601 with offset (reschedule only)"));
        return p;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String action = String.valueOf(arguments.getOrDefault("action", "")).strip().toLowerCase(Locale.ROOT);
        long id;
        try {
            id = Long.parseLong(String.valueOf(arguments.getOrDefault("job_id", "")).strip());
        } catch (NumberFormatException e) {
            return ToolResult.failure("job_id is missing -- the reminder id from the active list.");
        }
        return switch (action) {
            case "cancel" -> {
                boolean done = link.cancel(id);
                log.info("Reminder #{}: cancel -- {}", id, done ? "done" : "already inactive");
                yield done ? ToolResult.ok("Reminder #" + id + " cancelled.")
                        : ToolResult.failure("Reminder #" + id + " is not among the active ones -- it may have fired or been cancelled.");
            }
            case "reschedule" -> {
                String raw = String.valueOf(arguments.getOrDefault("fire_at", "")).strip();
                Instant at = parse(raw);
                if (at == null) {
                    yield ToolResult.failure("Cannot parse fire_at «" + raw + "». ISO-8601 with offset is required.");
                }
                if (!at.isAfter(clock.instant())) {
                    yield ToolResult.failure("The moment " + raw + " is already in the past. Recompute it.");
                }
                boolean done = link.reschedule(id, at);
                String when = ZonedDateTime.ofInstant(at, clock.getZone()).format(HUMAN);
                log.info("Reminder #{}: reschedule to {} -- {}", id, when, done ? "done" : "inactive");
                yield done ? ToolResult.ok("Reminder #" + id + " rescheduled to " + when + ".")
                        : ToolResult.failure("Reminder #" + id + " is not among the active ones.");
            }
            default -> ToolResult.failure("Unknown action «" + action + "»: cancel or reschedule expected.");
        };
    }

    private Instant parse(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw).atZone(clock.getZone()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
