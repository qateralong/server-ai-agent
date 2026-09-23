package com.bebebe.agent.tools.memory;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Write something down now, because the user just asked for it.
 *
 * <p>Until this existed, everything was remembered by background extraction: "запомни, что…" went
 * into the session log and waited for six more messages or ten minutes, and the "записал" in the
 * reply was a promise the agent had no way of keeping if the process died in between. Worse, the
 * agent could not tell the difference between a fact it had stored and one it had merely read.
 *
 * <p>Writing from two places does not double the facts: the same near-duplicate check that
 * protects background extraction runs here too, and a repeat is counted as a confirmation.
 */
public final class RememberTool implements Tool {

    public static final String NAME = "remember";

    private static final Logger log = LoggerFactory.getLogger(RememberTool.class);

    private final MemoryAccess memory;

    public RememberTool(MemoryAccess memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Write one fact into long-term memory right now. Call it when the user asks you to "
                + "remember something («запомни», «не забудь, что…», «на будущее»), or corrects "
                + "something you had remembered wrongly. For a correction pass replaces with the "
                + "number of the old fact (#N shown next to it) -- the old one stops being current "
                + "and the history stays. Everything else the user says is remembered by itself, "
                + "without this call, so do not use it to log the conversation.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text", Map.of("type", "string",
                "description", "the fact itself, one short sentence in Russian, in the third person: "
                        + "«Пользователь переехал в Москву»"));
        parameters.put("category", Map.of("type", "string",
                "description", "event | preference | agreement | trait | procedure"));
        parameters.put("about", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "names of the people it is about; empty when it is about the user"));
        parameters.put("replaces", Map.of("type", "integer",
                "description", "the number of the fact this one corrects, or 0"));
        parameters.put("keywords", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "3-6 other words a later question about this might use, not "
                        + "repeating the words of the text: «переезд», «город», «жильё»"));
        return parameters;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String text = RecallTool.string(arguments, "text");
        if (text.isEmpty()) {
            return ToolResult.failure("remember needs text: what exactly to write down.");
        }
        String category = RecallTool.string(arguments, "category");
        long replaces = longValue(arguments, "replaces");

        MemoryAccess.Outcome outcome = memory.remember(text, category, names(arguments, "about"),
                replaces, names(arguments, "keywords"));
        log.atInfo()
                .addKeyValue("event", "memory.remember")
                .addKeyValue("text", text)
                .addKeyValue("category", category)
                .addKeyValue("replaces", replaces)
                .addKeyValue("saved", outcome.ok())
                .log("Remember on request: {}", outcome.message());
        return outcome.ok() ? ToolResult.ok(outcome.message()) : ToolResult.failure(outcome.message());
    }

    private static List<String> names(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        List<String> names = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item != null && !item.toString().isBlank()) {
                    names.add(item.toString().strip());
                }
            }
        } else if (value != null && !value.toString().isBlank()) {

            // The model regularly sends one name as a bare string although the schema says array.
            names.add(value.toString().strip());
        }
        return names;
    }

    static long longValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString().strip().replace("#", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
