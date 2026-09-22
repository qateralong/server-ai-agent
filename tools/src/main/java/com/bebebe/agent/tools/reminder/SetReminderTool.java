package com.bebebe.agent.tools.reminder;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SetReminderTool implements Tool {

    public static final String NAME = "set_reminder";

    private static final Logger log = LoggerFactory.getLogger(SetReminderTool.class);
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.ENGLISH);

    static final Duration MAX_AHEAD = Duration.ofDays(366);

    @FunctionalInterface
    public interface Sink {

        long schedule(Instant fireAt, String prompt, String summary, String repeat);
    }

    private final Sink sink;
    private final Clock clock;

    public SetReminderTool(Sink sink) {
        this(sink, Clock.systemDefaultZone());
    }

    public SetReminderTool(Sink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Set a reminder for a specific moment. Use it when asked \"напомни\", \"не забудь сказать\", "
                + "\"через час скажи\", \"каждое утро\". Compute the moment yourself: the current time is "
                + "given to you (or take get_current_time); convert relative \"in 2 hours\" and "
                + "\"tomorrow at 9\" into an absolute date.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fire_at", Map.of("type", "string",
                "description", "firing moment in ISO-8601 with offset: 2026-09-19T17:00:00+03:00"));
        p.put("prompt", Map.of("type", "string",
                "description", "a message to yourself at that moment: what to remind and why, "
                        + "e.g. \"Time to remind the user to see the doctor; they asked at 15:00\""));
        p.put("summary", Map.of("type", "string",
                "description", "2-5 words for the reminder list, in Russian: \"сходить к врачу\""));
        p.put("repeat", Map.of("type", "string",
                "description", "empty -- once; daily -- every day; weekly:MON,WED -- on weekdays"));
        return p;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String rawWhen = String.valueOf(arguments.getOrDefault("fire_at", "")).strip();
        String prompt = String.valueOf(arguments.getOrDefault("prompt", "")).strip();
        String summary = String.valueOf(arguments.getOrDefault("summary", "")).strip();
        String repeat = String.valueOf(arguments.getOrDefault("repeat", "")).strip();

        if (rawWhen.isEmpty()) {
            return ToolResult.failure("fire_at is missing. Compute it from the current time and pass it as ISO-8601.");
        }
        if (prompt.isEmpty()) {
            prompt = summary.isEmpty() ? "Time to remind the user about what they asked." : "Time to remind the user: " + summary;
        }
        if (summary.isEmpty()) {
            summary = prompt.length() <= 40 ? prompt : prompt.substring(0, 39) + "…";
        }

        Instant fireAt;
        try {
            fireAt = parse(rawWhen);
        } catch (DateTimeParseException e) {
            return ToolResult.failure("Cannot parse fire_at «" + rawWhen
                    + "». ISO-8601 with offset is required, e.g. 2026-09-19T17:00:00+03:00.");
        }

        Instant now = clock.instant();
        if (!fireAt.isAfter(now)) {
            return ToolResult.failure("The moment " + rawWhen + " is already in the past (now "
                    + ZonedDateTime.now(clock).format(HUMAN) + "). Recompute it.");
        }
        if (Duration.between(now, fireAt).compareTo(MAX_AHEAD) > 0) {
            return ToolResult.failure("The moment is more than a year away -- looks like a date error. Check it.");
        }

        long id = sink.schedule(fireAt, prompt, summary, repeat);
        String when = ZonedDateTime.ofInstant(fireAt, clock.getZone()).format(HUMAN);
        Duration until = Duration.between(now, fireAt);
        log.info("Reminder #{} at {} ({}): {}", id, when, humanDuration(until), summary);
        return ToolResult.ok("Reminder #%d set for %s (in %s)%s: %s"
                .formatted(id, when, humanDuration(until),
                        repeat.isEmpty() ? "" : ", repeat: " + repeat, summary));
    }

    Instant parse(String raw) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            return java.time.LocalDateTime.parse(raw).atZone(clock.getZone()).toInstant();
        }
    }

    public static String humanDuration(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "less than a minute";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = d.toHours();
        if (hours < 24) {
            long rest = minutes - hours * 60;
            return hours + " h" + (rest > 0 ? " " + rest + " min" : "");
        }
        long days = d.toDays();
        long restHours = hours - days * 24;
        return days + " d" + (restHours > 0 ? " " + restHours + " h" : "");
    }
}
