package com.bebebe.agent.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LogBufferAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        Map<String, Object> kv = Map.of();
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null && !pairs.isEmpty()) {
            kv = new LinkedHashMap<>();
            for (KeyValuePair p : pairs) {
                kv.put(p.key, p.value);
            }
        }
        String trace = event.getMDCPropertyMap().getOrDefault(TraceContext.KEY, "");
        IThrowableProxy t = event.getThrowableProxy();
        String error = t == null ? null : t.getClassName() + ": " + t.getMessage();
        LogBuffer.global().add(new LogEntry(0, Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(), JsonLayout.subsystemOf(event.getLoggerName()),
                event.getLoggerName(), event.getThreadName(), trace == null ? "" : trace,
                event.getFormattedMessage(), kv, error));
    }
}
