package com.bebebe.agent.tools.memory;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Search long-term memory on purpose, instead of hoping the keyword pre-load guessed right.
 *
 * <p>Facts are put in front of the model before every decision, chosen by the words of the
 * message. When the wording of the question and the wording of the fact have no word in common --
 * which is most of the interesting cases -- that pre-load finds nothing, and the model has no way
 * to tell "I was told nothing" from "nothing was offered to me this time". This is that way.
 */
public final class RecallTool implements Tool {

    public static final String NAME = "recall";

    private static final Logger log = LoggerFactory.getLogger(RecallTool.class);

    private final MemoryAccess memory;

    public RecallTool(MemoryAccess memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search your long-term memory -- facts about the user and the people they know, and "
                + "summaries of past conversations. Call it when the answer depends on something the "
                + "user told you earlier and it is not in the context of this conversation: «что я "
                + "говорил про…», «кто из моих знакомых…», «как мы договорились», «что мы обсуждали "
                + "вчера», «ты помнишь, что…», or when you are about to say that you do not remember "
                + "something -- check first. Facts already shown to you above do not need this call. "
                + "Not for the current conversation (you can see it) and not for the internet (that is "
                + "web_search).";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", Map.of("type", "string",
                "description", "what to look for, in the user's own words: «вегетарианство», "
                        + "«переезд в Москву», «настройка whisper»"));
        parameters.put("about", Map.of("type", "string",
                "description", "a person's name, if the question is about somebody in particular; "
                        + "otherwise leave empty"));
        return parameters;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String query = string(arguments, "query");
        String about = string(arguments, "about");
        if (query.isEmpty() && about.isEmpty()) {
            return ToolResult.failure("recall needs a query: say what to look for.");
        }

        MemoryAccess.Recall recall = memory.recall(query, about);
        log.atInfo()
                .addKeyValue("event", "memory.recall")
                .addKeyValue("query", query)
                .addKeyValue("about", about)
                .addKeyValue("facts", recall.facts().size())
                .addKeyValue("episodes", recall.episodes().size())
                .log("Recall «{}»: {} facts, {} episodes", query, recall.facts().size(),
                        recall.episodes().size());

        if (recall.isEmpty()) {
            StringBuilder sb = new StringBuilder("Nothing remembered about «")
                    .append(query.isEmpty() ? about : query)
                    .append("». Say plainly that you do not remember this -- do not make something up "
                            + "and do not present a guess as a memory.");
            if (!recall.people().isEmpty()) {
                sb.append("\n\nPeople you do know: ").append(String.join(", ", recall.people()));
            }
            return ToolResult.failure(sb.toString());
        }

        StringBuilder sb = new StringBuilder();
        if (!recall.facts().isEmpty()) {
            sb.append("Remembered:\n");
            recall.facts().forEach(fact -> sb.append(fact).append('\n'));
        }
        if (!recall.episodes().isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("Past conversations about this:\n");
            recall.episodes().forEach(episode -> sb.append(episode).append('\n'));
        }
        sb.append("\nAnswer from this. The numbers are internal -- do not show them to the user.");
        return ToolResult.ok(sb.toString());
    }

    static String string(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().strip();
    }
}
