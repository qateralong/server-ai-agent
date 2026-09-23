package com.bebebe.agent.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The last check before a reply leaves for the user: does it look like the agent's own
 * plumbing rather than an answer?
 *
 * <p>This is a <b>safety net, not the fix</b>. Raw protocol JSON reaching the chat means the
 * decision loop failed to handle an unparseable answer, and a stack trace reaching it means an
 * error message was built out of an exception. Both are fixed where they happen; this only makes
 * sure that the next such mistake is a neutral sentence in the chat and a WARN in the log,
 * instead of the model's internals in front of the user.
 *
 * <p>It deliberately looks for markers that never occur in a real answer -- the names of protocol
 * fields, the shape of a stack trace -- rather than for "looks technical". An agent explaining
 * JSON to someone must still be able to show them JSON.
 */
public final class LeakGuard {

    /** Names that exist only inside the decision protocol and the tool schema. */
    private static final List<String> INTERNAL_MARKERS = List.of(
            "fix_last_script", "use_script", "run_script", "python_code", "tool_name",
            "script_id", "script_tags", "additionalProperties", "tool_call");

    /** A JSON key of the decision protocol, as it appears in a raw answer. */
    private static final Pattern PROTOCOL_KEY = Pattern.compile(
            "\"(type|reply|python_code|tool_name|script_id|explanation|arguments|messages)\"\\s*:");

    private static final Pattern JAVA_FRAME = Pattern.compile("(?m)^\\s*at [\\w.$]+\\([^)]*\\)");

    private static final Pattern EXCEPTION_LINE = Pattern.compile(
            "(?m)^(java|javax|jdk|com\\.bebebe|org\\.\\w+)[\\w.$]*(Exception|Error)\\b");

    private static final Pattern PYTHON_TRACE = Pattern.compile("Traceback \\(most recent call last\\)");

    private LeakGuard() {
    }

    /** What the user sees instead. Deliberately says nothing about what went wrong. */
    public static String neutralReply() {
        return "Не получилось сформулировать ответ. Попробуйте переформулировать запрос.";
    }

    /**
     * @return the reason it looks like a leak, or null when the text is fine. A reason rather
     *         than a boolean so the log says which rule fired.
     */
    public static String suspect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String body = AgentDecision.stripFences(text);

        if (looksLikeJson(body) && PROTOCOL_KEY.matcher(body).find()) {
            return "raw protocol JSON";
        }
        if (JAVA_FRAME.matcher(body).find() || EXCEPTION_LINE.matcher(body).find()) {
            return "java stack trace";
        }
        if (PYTHON_TRACE.matcher(body).find()) {
            return "python traceback";
        }

        // Two different internal names in one message is not something an answer does; one
        // could be a person asking about this very project, so one is not enough.
        int markers = 0;
        for (String marker : INTERNAL_MARKERS) {
            if (body.contains(marker)) {
                markers++;
            }
        }
        if (markers >= 2) {
            return "internal protocol names (" + markers + ")";
        }
        return null;
    }

    private static boolean looksLikeJson(String body) {
        String trimmed = body.strip();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }
}
