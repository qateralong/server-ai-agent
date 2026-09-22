package com.bebebe.agent.tools.reminder;

import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManageRemindersToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Europe/Moscow"));
    private static final ToolContext NO_LLM = new ToolContext((s, u, sc) -> { throw new AssertionError(); }, "");

    private final List<String> calls = new ArrayList<>();
    private final ManageRemindersTool tool = new ManageRemindersTool(new ManageRemindersTool.Link() {
        @Override
        public boolean cancel(long jobId) {
            calls.add("cancel " + jobId);
            return jobId == 7;
        }

        @Override
        public boolean reschedule(long jobId, Instant fireAt) {
            calls.add("reschedule " + jobId + " " + fireAt);
            return jobId == 7;
        }
    }, FIXED);

    @Test
    void cancelById() {
        ToolResult r = tool.execute(Map.of("action", "cancel", "job_id", 7), NO_LLM);

        assertTrue(r.success(), r.content());
        assertEquals(List.of("cancel 7"), calls);
    }

    @Test
    void cancellingInactiveIsErrorWithExplanation() {
        ToolResult r = tool.execute(Map.of("action", "cancel", "job_id", 8), NO_LLM);

        assertFalse(r.success());
        assertTrue(r.content().contains("may have fired"), r.content());
    }

    @Test
    void rescheduleKeepsId() {
        ToolResult r = tool.execute(Map.of("action", "reschedule", "job_id", 7,
                "fire_at", "2026-09-19T18:00:00+03:00"), NO_LLM);

        assertTrue(r.success(), r.content());
        assertEquals(List.of("reschedule 7 2026-09-19T15:00:00Z"), calls);
        assertTrue(r.content().contains("19 September, 18:00"), r.content());
    }

    @Test
    void rescheduleToPastIsRejected() {
        ToolResult r = tool.execute(Map.of("action", "reschedule", "job_id", 7,
                "fire_at", "2026-09-19T10:00:00+03:00"), NO_LLM);

        assertFalse(r.success());
        assertTrue(calls.isEmpty());
    }

    @Test
    void withoutIdPoliteError() {
        assertFalse(tool.execute(Map.of("action", "cancel"), NO_LLM).success());
        assertFalse(tool.execute(Map.of("action", "fly", "job_id", 1), NO_LLM).success());
    }
}
