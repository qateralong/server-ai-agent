package com.bebebe.agent.tools.time;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;

public final class GetCurrentTimeTool implements Tool {

    public static final String NAME = "get_current_time";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;

    public GetCurrentTimeTool() {
        this(Clock.systemDefaultZone());
    }

    public GetCurrentTimeTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Current date, time and day of week. Use it for questions about \"today\", "
                + "\"now\", \"what day\", \"how many days until\" -- you do not know this yourself.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("timezone", Map.of(
                "type", "string",
                "description", "time zone like Europe/Moscow; empty -- local"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Object raw = arguments.get("timezone");
        ZoneId zone = clock.getZone();
        if (raw != null && !raw.toString().isBlank()) {
            try {
                zone = ZoneId.of(raw.toString().strip());
            } catch (DateTimeException e) {
                return ToolResult.failure("Unknown time zone: " + raw);
            }
        }

        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String text = """
                Date: %s
                Day of week: %s
                Time: %s
                Time zone: %s (UTC%s)
                ISO: %s"""
                .formatted(
                        now.format(DATE),
                        weekday,
                        now.format(TIME),
                        zone.getId(),
                        now.getOffset().getId().equals("Z") ? "+00:00" : now.getOffset().getId(),
                        now.toOffsetDateTime().toString());
        return ToolResult.ok(text);
    }
}
