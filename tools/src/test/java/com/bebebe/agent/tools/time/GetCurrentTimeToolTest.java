package com.bebebe.agent.tools.time;

import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetCurrentTimeToolTest {

    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-09-19T11:30:00Z"), ZoneId.of("Europe/Moscow"));

    private static final ToolContext NO_LLM = new ToolContext(
            (system, user, schema) -> { throw new AssertionError("the time tool must not call the model"); },
            "который час?");

    @Test
    void returnsDateTimeAndWeekday() {
        ToolResult result = new GetCurrentTimeTool(FIXED).execute(Map.of(), NO_LLM);

        assertTrue(result.success());
        assertTrue(result.content().contains("19 September 2026"), result.content());
        assertTrue(result.content().contains("Saturday"), result.content());
        assertTrue(result.content().contains("14:30"), result.content());
        assertTrue(result.content().contains("Europe/Moscow"));
    }

    @Test
    void honoursTimeZoneFromArguments() {
        ToolResult result = new GetCurrentTimeTool(FIXED).execute(Map.of("timezone", "Asia/Tokyo"), NO_LLM);

        assertTrue(result.content().contains("20:30"), result.content());
        assertTrue(result.content().contains("Asia/Tokyo"));
    }

    @Test
    void unknownZoneIsErrorNotCrash() {
        ToolResult result = new GetCurrentTimeTool(FIXED).execute(Map.of("timezone", "Марс/Кратер"), NO_LLM);

        assertFalse(result.success());
        assertTrue(result.content().contains("time zone"));
    }

    @Test
    void deterministic() {
        GetCurrentTimeTool tool = new GetCurrentTimeTool(FIXED);
        assertTrue(tool.execute(Map.of(), NO_LLM).content()
                .equals(tool.execute(Map.of(), NO_LLM).content()));
    }
}
