package com.bebebe.agent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentDecision(
        DecisionType type,
        String reply,
        List<String> messages,
        String pythonCode,
        String explanation,
        long scriptId,
        String scriptName,
        List<String> scriptTags,
        String toolName,
        Map<String, Object> arguments
) {

    public AgentDecision {
        type = type == null ? DecisionType.UNKNOWN : type;
        messages = messages == null ? List.of() : messages.stream()
                .filter(m -> m != null && !m.isBlank()).map(String::strip).toList();

        reply = reply == null || reply.isBlank()
                ? String.join("\n\n", messages)
                : reply.strip();
        pythonCode = pythonCode == null ? "" : pythonCode.strip();
        explanation = explanation == null ? "" : explanation.strip();
        scriptName = scriptName == null ? "" : scriptName.strip();
        scriptTags = scriptTags == null ? List.of() : List.copyOf(scriptTags);
        toolName = toolName == null ? "" : toolName.strip();
        arguments = arguments == null ? Map.of() : withoutNulls(arguments);
    }

    public List<String> replies() {
        return messages.isEmpty() ? (reply.isEmpty() ? List.of() : List.of(reply)) : messages;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        source.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    public static AgentDecision reply(String text) {
        return new AgentDecision(DecisionType.REPLY, text, List.of(), "", "", 0, "", List.of(), "", Map.of());
    }

    public static AgentDecision reply(String text, List<String> messages) {
        return new AgentDecision(DecisionType.REPLY, text, messages, "", "", 0, "", List.of(), "", Map.of());
    }

    public static AgentDecision runScript(String code, String name, String explanation) {
        return new AgentDecision(DecisionType.RUN_SCRIPT, "", List.of(), code, explanation, 0, name, List.of(), "", Map.of());
    }

    public static AgentDecision useScript(long scriptId) {
        return new AgentDecision(DecisionType.USE_SCRIPT, "", List.of(), "", "", scriptId, "", List.of(), "", Map.of());
    }

    public static AgentDecision fixLastScript(String code, String explanation) {
        return new AgentDecision(DecisionType.FIX_LAST_SCRIPT, "", List.of(), code, explanation, 0, "", List.of(), "", Map.of());
    }

    public static AgentDecision toolCall(String toolName, Map<String, Object> arguments) {
        return new AgentDecision(DecisionType.TOOL_CALL, "", List.of(), "", "", 0, "", List.of(), toolName, arguments);
    }

    public static AgentDecision unknown() {
        return new AgentDecision(DecisionType.UNKNOWN, "", List.of(), "", "", 0, "", List.of(), "", Map.of());
    }

    public static AgentDecision parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return unknown();
        }
        try {
            Map<?, ?> raw = mapper.readValue(stripFences(json), Map.class);
            raw = flatten(raw);
            DecisionType type = DecisionType.fromWire(stringAt(raw, "type"));
            if (type == DecisionType.UNKNOWN) {
                type = inferType(raw);
            }
            return new AgentDecision(
                    type,
                    stringAt(raw, "reply"),
                    tagsAt(raw, "messages"),
                    stringAt(raw, "python_code"),
                    stringAt(raw, "explanation"),
                    longAt(raw, "script_id"),
                    stringAt(raw, "script_name"),
                    tagsAt(raw, "script_tags"),
                    stringAt(raw, "tool_name"),
                    mapAt(raw, "arguments"));
        } catch (IOException | RuntimeException e) {
            return unknown();
        }
    }

    static String stripFences(String json) {
        String text = json.strip();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            text = firstBreak < 0 ? "" : text.substring(firstBreak + 1);
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.strip();
        }
        return text;
    }

    private static Map<?, ?> flatten(Map<?, ?> raw) {
        for (DecisionType type : DecisionType.values()) {
            if (type == DecisionType.UNKNOWN) {
                continue;
            }
            Object nested = raw.get(type.wireName());
            if (nested instanceof Map<?, ?> inner && !inner.isEmpty()) {
                Map<String, Object> flat = new java.util.LinkedHashMap<>();
                raw.forEach((k, v) -> flat.put(String.valueOf(k), v));
                inner.forEach((k, v) -> flat.put(String.valueOf(k), v));
                flat.put("type", type.wireName());
                return flat;
            }
        }
        return raw;
    }

    private static DecisionType inferType(Map<?, ?> raw) {
        if (raw.get("messages") instanceof List<?> list && !list.isEmpty()) {
            return DecisionType.REPLY;
        }
        if (!stringAt(raw, "tool_name").isEmpty()) {
            return DecisionType.TOOL_CALL;
        }
        if (longAt(raw, "script_id") > 0) {
            return DecisionType.USE_SCRIPT;
        }
        if (!stringAt(raw, "python_code").isEmpty()) {
            return DecisionType.RUN_SCRIPT;
        }
        if (!stringAt(raw, "reply").isEmpty()) {
            return DecisionType.REPLY;
        }
        return DecisionType.UNKNOWN;
    }

    public boolean isUsable() {
        return switch (type) {
            case REPLY -> !reply.isEmpty();
            case RUN_SCRIPT, FIX_LAST_SCRIPT -> !pythonCode.isEmpty();
            case USE_SCRIPT -> scriptId > 0;
            case TOOL_CALL -> !toolName.isEmpty();
            case UNKNOWN -> false;
        };
    }

    private static String stringAt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static long longAt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString().strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (value instanceof String s && s.strip().startsWith("{")) {
            try {
                return new ObjectMapper().readValue(s, Map.class);
            } catch (IOException e) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private static List<String> tagsAt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).map(String::strip)
                    .filter(s -> !s.isEmpty()).toList();
        }
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.toString().split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public String toString() {
        return switch (type) {
            case REPLY -> "reply(" + preview(reply) + ")";
            case RUN_SCRIPT -> "run_script(" + pythonCode.lines().count() + " lines, '" + scriptName + "')";
            case USE_SCRIPT -> "use_script(" + scriptId + ")";
            case FIX_LAST_SCRIPT -> "fix_last_script(" + pythonCode.lines().count() + " lines)";
            case TOOL_CALL -> "tool_call(" + toolName + " " + arguments + ")";
            case UNKNOWN -> "unknown";
        };
    }

    private static String preview(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
