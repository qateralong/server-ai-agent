package com.bebebe.agent.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.event.KeyValuePair;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public final class JsonLayout extends LayoutBase<ILoggingEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault());

    private static final int MAX_STACK_FRAMES = 30;

    private static final List<Map.Entry<String, String>> SUBSYSTEMS = List.of(
            Map.entry("com.bebebe.agent.script", "script-runtime"),
            Map.entry("com.bebebe.agent.core", "agent-core"),
            Map.entry("com.bebebe.agent.telegram", "telegram-bridge"),
            Map.entry("com.bebebe.agent.scheduler", "scheduler"),
            Map.entry("com.bebebe.agent.notes", "notes-store"),
            Map.entry("com.bebebe.agent.memory", "memory-store"),
            Map.entry("com.bebebe.agent.tools", "tools"),
            Map.entry("com.bebebe.agent.watchdog", "watchdog"),
            Map.entry("com.bebebe.agent.transport", "transport"),
            Map.entry("com.bebebe.agent.stt", "stt-bridge"),
            Map.entry("com.bebebe.agent.tts", "tts-bridge"),
            Map.entry("com.bebebe.agent.ollama", "ollama-client"),
            Map.entry("com.bebebe.agent.ui", "ui"),
            Map.entry("com.bebebe.agent.config", "config-store"),
            Map.entry("com.bebebe.agent.supervisor", "supervisor"),
            Map.entry("com.bebebe.agent.assembly", "supervisor"),
            Map.entry("com.bebebe.agent.server", "server"),
            Map.entry("com.bebebe.agent.client", "client"),
            Map.entry("com.bebebe.agent.capture", "stt-bridge"),
            Map.entry("com.bebebe.agent.logging", "logging"),
            Map.entry("com.bebebe.agent", "other"));

    public static String subsystemOf(String loggerName) {
        if (loggerName == null) {
            return "external";
        }
        for (Map.Entry<String, String> entry : SUBSYSTEMS) {
            if (loggerName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "external";
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ts", TIMESTAMP.format(Instant.ofEpochMilli(event.getTimeStamp())));
        node.put("level", event.getLevel().toString());
        node.put("subsystem", subsystemOf(event.getLoggerName()));
        node.put("logger", abbreviate(event.getLoggerName()));
        node.put("thread", event.getThreadName());

        Map<String, String> mdc = event.getMDCPropertyMap();
        String traceId = mdc == null ? null : mdc.get(TraceContext.KEY);
        if (traceId != null && !traceId.isBlank()) {
            node.put(TraceContext.KEY, traceId);
        }

        node.put("msg", event.getFormattedMessage());

        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null && !pairs.isEmpty()) {
            ObjectNode kv = node.putObject("kv");
            for (KeyValuePair pair : pairs) {
                putValue(kv, pair.key, pair.value);
            }
        }

        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            node.set("error", describe(error));
        }

        try {
            return MAPPER.writeValueAsString(node) + System.lineSeparator();
        } catch (JsonProcessingException e) {

            return "{\"level\":\"ERROR\",\"msg\":\"JsonLayout: " + e.getMessage() + "\"}"
                    + System.lineSeparator();
        }
    }

    private static void putValue(ObjectNode target, String key, Object value) {
        switch (value) {
            case null -> target.putNull(key);
            case Number n -> target.putPOJO(key, n);
            case Boolean b -> target.put(key, b);
            case String s -> target.put(key, s);
            default -> target.put(key, String.valueOf(value));
        }
    }

    private static ObjectNode describe(IThrowableProxy error) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", error.getClassName());
        node.put("message", error.getMessage());

        StringBuilder stack = new StringBuilder();
        StackTraceElementProxy[] frames = error.getStackTraceElementProxyArray();
        int limit = Math.min(frames.length, MAX_STACK_FRAMES);
        for (int i = 0; i < limit; i++) {
            stack.append(frames[i].getSTEAsString()).append('\n');
        }
        if (frames.length > limit) {
            stack.append("... ").append(frames.length - limit).append(" more frames\n");
        }
        node.put("stack", stack.toString());

        if (error.getCause() != null && error.getCause() != error) {
            node.set("cause", describe(error.getCause()));
        }
        return node;
    }

    private static String abbreviate(String loggerName) {
        if (loggerName == null) {
            return "";
        }
        String[] parts = loggerName.split("\\.");
        if (parts.length <= 3) {
            return loggerName;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i < parts.length - 3) {
                sb.append(parts[i].charAt(0)).append('.');
            } else {
                sb.append(parts[i]);
                if (i < parts.length - 1) {
                    sb.append('.');
                }
            }
        }
        return sb.toString();
    }
}
