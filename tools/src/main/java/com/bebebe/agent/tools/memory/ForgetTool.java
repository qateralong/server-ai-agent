package com.bebebe.agent.tools.memory;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retract a fact the user says is wrong.
 *
 * <p>The menu could already delete facts, but only the menu: in a conversation "нет, это неправда"
 * changed nothing, and the wrong fact went on being offered to the model for ever. The number to
 * pass comes from the {@code #N} shown next to every remembered fact.
 *
 * <p>Nothing is erased -- the fact stops being current. Memory that silently drops things is hard
 * to trust and impossible to debug.
 */
public final class ForgetTool implements Tool {

    public static final String NAME = "forget";

    private static final Logger log = LoggerFactory.getLogger(ForgetTool.class);

    private final MemoryAccess memory;

    public ForgetTool(MemoryAccess memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Stop believing a fact you remembered: the user says it is wrong or no longer true "
                + "(«это неправда», «я больше там не работаю», «забудь про это»). Pass the number #N "
                + "shown next to the fact. If instead of removing it the user is replacing it with "
                + "something else, use remember with replaces -- that keeps the correction connected "
                + "to what it corrects.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fact_id", Map.of("type", "integer",
                "description", "the number #N of the fact, as shown next to it"));
        parameters.put("reason", Map.of("type", "string",
                "description", "why, in a few words -- it goes into the log"));
        return parameters;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        long factId = RememberTool.longValue(arguments, "fact_id");
        if (factId <= 0) {
            return ToolResult.failure("forget needs fact_id -- the number #N shown next to the fact. "
                    + "If you cannot see it, call recall first.");
        }
        String reason = RecallTool.string(arguments, "reason");
        MemoryAccess.Outcome outcome = memory.forget(factId, reason);
        log.atInfo()
                .addKeyValue("event", "memory.forget")
                .addKeyValue("fact_id", factId)
                .addKeyValue("reason", reason)
                .addKeyValue("done", outcome.ok())
                .log("Forget #{}: {}", factId, outcome.message());
        return outcome.ok() ? ToolResult.ok(outcome.message()) : ToolResult.failure(outcome.message());
    }
}
