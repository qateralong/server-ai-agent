package com.bebebe.agent.tools.reminder;

import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetReminderToolTest {

    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Europe/Moscow"));

    private static final ToolContext NO_LLM = new ToolContext(
            (system, user, schema) -> { throw new AssertionError("the reminder tool does not call the model"); },
            "напомни");

    private record Scheduled(Instant fireAt, String prompt, String summary, String repeat) { }

    private final List<Scheduled> scheduled = new ArrayList<>();
    private final SetReminderTool tool = new SetReminderTool((at, p, s, r) -> {
        scheduled.add(new Scheduled(at, p, s, r));
        return 7L;
    }, FIXED);

    @Test
    void setsReminderAndReportsHumanly() {
        ToolResult result = tool.execute(Map.of(
                "fire_at", "2026-09-19T17:00:00+03:00",
                "prompt", "Пора напомнить про врача",
                "summary", "врач"), NO_LLM);

        assertTrue(result.success(), result.content());
        assertEquals(1, scheduled.size());
        assertEquals(Instant.parse("2026-09-19T14:00:00Z"), scheduled.getFirst().fireAt());
        assertEquals("Пора напомнить про врача", scheduled.getFirst().prompt());
        assertEquals("", scheduled.getFirst().repeat());
        assertTrue(result.content().contains("#7"), result.content());
        assertTrue(result.content().contains("19 September, 17:00"), result.content());
        assertTrue(result.content().contains("in 2 h"), result.content());
    }

    @Test
    void timeWithoutOffsetIsLocal() {
        tool.execute(Map.of("fire_at", "2026-09-19T17:00:00", "summary", "x"), NO_LLM);

        assertEquals(Instant.parse("2026-09-19T14:00:00Z"), scheduled.getFirst().fireAt());
    }

    @Test
    void momentInPastIsRejected() {
        ToolResult result = tool.execute(Map.of("fire_at", "2026-09-19T14:00:00+03:00", "summary", "x"), NO_LLM);

        assertFalse(result.success());
        assertTrue(result.content().contains("in the past"), result.content());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void moreThanYearAwayIsDateError() {
        ToolResult result = tool.execute(Map.of("fire_at", "2028-01-01T10:00:00+03:00", "summary", "x"), NO_LLM);

        assertFalse(result.success());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void garbageInsteadOfDateIsErrorNotCrash() {
        ToolResult result = tool.execute(Map.of("fire_at", "завтра в девять", "summary", "x"), NO_LLM);

        assertFalse(result.success());
        assertTrue(result.content().contains("ISO-8601"), result.content());
    }

    @Test
    void withoutMomentNotSet() {
        Map<String, Object> args = new HashMap<>();
        args.put("fire_at", null);
        args.put("summary", "x");

        ToolResult result = tool.execute(args, NO_LLM);

        assertFalse(result.success());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void promptAndSummaryComplementEachOther() {
        tool.execute(Map.of("fire_at", "2026-09-19T17:00:00+03:00", "summary", "полить цветы"), NO_LLM);
        tool.execute(Map.of("fire_at", "2026-09-19T17:00:00+03:00",
                "prompt", "Пора напомнить пользователю выключить духовку, он ставил пирог"), NO_LLM);

        assertEquals("Time to remind the user: полить цветы", scheduled.get(0).prompt());
        assertEquals("полить цветы", scheduled.get(0).summary());
        assertTrue(scheduled.get(1).summary().endsWith("…"), scheduled.get(1).summary());
        assertTrue(scheduled.get(1).summary().length() <= 40);
    }

    @Test
    void repeatIsPassedAsIs() {
        ToolResult result = tool.execute(Map.of("fire_at", "2026-09-20T09:00:00+03:00",
                "summary", "зарядка", "repeat", "daily"), NO_LLM);

        assertEquals("daily", scheduled.getFirst().repeat());
        assertTrue(result.content().contains("repeat: daily"), result.content());
    }

    @Test
    void humanDuration() {
        assertEquals("less than a minute", SetReminderTool.humanDuration(Duration.ofSeconds(30)));
        assertEquals("5 min", SetReminderTool.humanDuration(Duration.ofMinutes(5)));
        assertEquals("2 h", SetReminderTool.humanDuration(Duration.ofHours(2)));
        assertEquals("2 h 15 min", SetReminderTool.humanDuration(Duration.ofMinutes(135)));
        assertEquals("3 d", SetReminderTool.humanDuration(Duration.ofDays(3)));
        assertEquals("1 d 6 h", SetReminderTool.humanDuration(Duration.ofHours(30)));
    }
}
