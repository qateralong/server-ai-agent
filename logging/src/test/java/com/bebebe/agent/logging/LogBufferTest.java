package com.bebebe.agent.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogBufferTest {

    @Test
    void loggerLinesReachBufferWithSubsystemAndTraceId() {
        LogBuffer buffer = LogBuffer.global();
        int before = buffer.size();
        try (TraceContext.Scope ignored = TraceContext.open("abc123abc123")) {
            LoggerFactory.getLogger("com.bebebe.agent.core.Тест").atWarn()
                    .addKeyValue("event", "test.event").log("проверка буфера");
        }

        List<LogEntry> tail = buffer.snapshot(e -> e.message().equals("проверка буфера"), 10);

        assertEquals(1, tail.size());
        LogEntry e = tail.getFirst();
        assertEquals("WARN", e.level());
        assertEquals("agent-core", e.subsystem());
        assertEquals("abc123abc123", e.traceId());
        assertEquals("test.event", e.kv().get("event"));
        assertTrue(e.displayLine().contains("[abc123abc123] проверка буфера"), e.displayLine());
        assertTrue(buffer.size() > before);
    }

    @Test
    void lastErrorIsRememberedSeparately() {
        LoggerFactory.getLogger("com.bebebe.agent.telegram.Тест").error("упало", new IllegalStateException("причина"));

        LogEntry err = LogBuffer.global().lastError().orElseThrow();

        assertEquals("упало", err.message());
        assertTrue(err.error().contains("IllegalStateException: причина"), err.error());
        assertEquals("telegram-bridge", err.subsystem());
    }

    @Test
    void bufferIsCircular() {
        LogBuffer small = new LogBuffer(3);
        for (int i = 1; i <= 5; i++) {
            small.add(new LogEntry(0, java.time.Instant.EPOCH, "INFO", "x", "x", "t", "", "m" + i, java.util.Map.of(), null));
        }

        assertEquals(List.of("m3", "m4", "m5"), small.snapshot().stream().map(LogEntry::message).toList());
        assertEquals(List.of("m5"), small.snapshot(e -> true, 1).stream().map(LogEntry::message).toList());
    }
}
