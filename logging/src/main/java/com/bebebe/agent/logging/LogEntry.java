package com.bebebe.agent.logging;

import java.time.Instant;
import java.util.Map;

public record LogEntry(long seq, Instant ts, String level, String subsystem, String logger, String thread,
                       String traceId, String message, Map<String, Object> kv, String error) {

    public boolean isError() {
        return "ERROR".equals(level);
    }

    public String displayLine() {
        String t = ts.toString();
        int tIdx = t.indexOf('T');
        String hhmmss = tIdx > 0 && t.length() >= tIdx + 13 ? t.substring(tIdx + 1, tIdx + 13) : t;
        return "%s %-5s %-15s %s%s".formatted(hhmmss, level, subsystem,
                traceId.isEmpty() ? "" : "[" + traceId + "] ", message);
    }
}
