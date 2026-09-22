package com.bebebe.agent.scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record Job(
        long id,
        Instant fireAt,
        String prompt,
        String summary,
        String conversationKey,
        Repeat repeat,
        JobStatus status,
        Instant createdAt,
        Instant firedAt,
        String traceId
) {

    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.of("ru"));

    public Job {
        prompt = prompt == null ? "" : prompt.strip();
        summary = summary == null ? "" : summary.strip();
        conversationKey = conversationKey == null ? "" : conversationKey;
        repeat = repeat == null ? Repeat.ONCE : repeat;
        status = status == null ? JobStatus.PENDING : status;
    }

    public boolean isDue(Instant now) {
        return status == JobStatus.PENDING && !fireAt.isAfter(now);
    }

    public String displayLine(ZoneId zone) {
        String when = ZonedDateTime.ofInstant(fireAt, zone).format(HUMAN);
        String what = summary.isEmpty() ? prompt : summary;
        String text = when + " · " + what;
        return text.length() <= 40 ? text : text.substring(0, 39) + "…";
    }

    public String fireAtHuman(ZoneId zone) {
        return ZonedDateTime.ofInstant(fireAt, zone).format(HUMAN);
    }
}
