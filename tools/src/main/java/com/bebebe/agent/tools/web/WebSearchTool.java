package com.bebebe.agent.tools.web;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WebSearchTool implements Tool {

    public static final String NAME = "web_search";

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebSearchConfig config;
    private final SearchProvider provider;
    private final PageFetcher fetcher;

    public WebSearchTool(WebSearchConfig config) {
        this(config, config.buildProvider(), new PageFetcher(config.timeout(), config.maxPageChars()));
    }

    public WebSearchTool(WebSearchConfig config, SearchProvider provider, PageFetcher fetcher) {
        this.config = config;
        this.provider = provider;
        this.fetcher = fetcher;
        log.info("web_search: provider {}, results {}, pages {}",
                provider.name(), config.maxResults(), config.fetchPages());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Web search that also reads the pages found. For anything that may have changed "
                + "after your training or changes constantly: exchange rates, weather, news, prices, "
                + "software versions, schedules, \"what is going on with ...\". And for conversation: when "
                + "the talk turns to something specific (a person, event, product, place, book, film, figures) "
                + "and you are unsure of the details -- better to search than to answer at random. Call it "
                + "yourself, the user does not have to say \"google it\"; but not on every message and not "
                + "about what is already in the conversation.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", Map.of("type", "string",
                "description", "what to search for, in your own words; the exact query is formulated separately"));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String topic = String.valueOf(arguments.getOrDefault("query", "")).strip();
        String question = context.question() == null || context.question().isBlank() ? topic : context.question();
        if (topic.isEmpty() && question.isBlank()) {
            return ToolResult.failure("Nothing to search for was given");
        }

        String query = formulateQuery(context, question, topic);
        List<SearchResult> results = List.of();
        String lastError = null;

        for (int attempt = 0; attempt <= config.maxReformulations(); attempt++) {
            try {
                results = provider.search(query, config.maxResults());
            } catch (WebSearchException e) {
                lastError = e.getMessage();
                log.warn("Search «{}» failed: {}", query, e.getMessage());
                break;
            }
            log.atInfo()
                    .addKeyValue("event", "web.search")
                    .addKeyValue("query", query)
                    .addKeyValue("attempt", attempt)
                    .addKeyValue("results", results.size())
                    .log("Search «{}»: {} results", query, results.size());

            if (results.isEmpty()) {
                Optional<String> next = reformulate(context, question, query, results, attempt);
                if (next.isEmpty()) {
                    break;
                }
                query = next.get();
                continue;
            }

            Optional<String> next = reformulate(context, question, query, results, attempt);
            if (next.isEmpty()) {
                break;
            }
            query = next.get();
        }

        if (results.isEmpty()) {
            return ToolResult.failure(lastError != null
                    ? "Search unavailable: " + lastError
                    : "Nothing found for «" + query + "»");
        }

        StringBuilder content = new StringBuilder();
        content.append("Query: ").append(query).append("\n\nResults:\n");
        for (int i = 0; i < results.size(); i++) {
            content.append(results.get(i).describe(i + 1)).append('\n');
        }

        List<String> sources = new ArrayList<>();
        int fetched = 0;
        for (SearchResult result : results) {
            if (fetched >= config.fetchPages()) {
                break;
            }
            Optional<String> text = fetcher.fetchText(result.url());
            if (text.isPresent()) {
                fetched++;
                sources.add(result.url());
                content.append("\n--- Page: ").append(result.title()).append(" (").append(result.url()).append(")\n")
                        .append(text.get()).append('\n');
            }
        }
        if (sources.isEmpty()) {
            results.stream().limit(3).forEach(r -> sources.add(r.url()));
        }

        return ToolResult.ok(content.toString(), String.join("\n", sources));
    }

    private String formulateQuery(ToolContext context, String question, String topic) {
        try {
            String answer = context.llm().ask(
                    "You formulate search queries. Answer ONLY with the query text: 3-8 words, "
                            + "no quotes or explanations, in the language in which the result is most likely "
                            + "to be found (Russian for Russian realities).",
                    "User question: " + question
                            + (topic.isEmpty() || topic.equals(question) ? "" : "\nSearch topic: " + topic)
                            + "\nToday: " + java.time.LocalDate.now(),
                    null);
            String cleaned = answer.strip().replaceAll("^[\"«]|[\"»]$", "").strip();
            if (!cleaned.isEmpty() && cleaned.length() <= 200) {
                return cleaned;
            }
        } catch (ToolContext.BudgetExhausted e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Failed to formulate the query: {}", e.getMessage());
        }
        return topic.isEmpty() ? question : topic;
    }

    private Optional<String> reformulate(ToolContext context, String question, String query,
                                         List<SearchResult> results, int attempt) {
        if (attempt >= config.maxReformulations()) {
            return Optional.empty();
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "relevant", Map.of("type", "boolean"),
                "new_query", Map.of("type", "string")));
        schema.put("required", List.of("relevant", "new_query"));
        schema.put("additionalProperties", false);

        StringBuilder sb = new StringBuilder("User question: ").append(question)
                .append("\nSearch query: ").append(query).append("\n\nResults:\n");
        if (results.isEmpty()) {
            sb.append("(empty)\n");
        }
        for (int i = 0; i < results.size(); i++) {
            sb.append(results.get(i).describe(i + 1)).append('\n');
        }
        sb.append("\nIf the question can be answered from these snippets -- relevant = true, new_query = \"\". "
                + "If not -- relevant = false and new_query with a different wording (other words, "
                + "a place/date refinement, another language).");

        try {
            String answer = context.llm().ask(
                    "You evaluate search results. Answer only with JSON per the schema.", sb.toString(), schema);
            JsonNode node = MAPPER.readTree(answer);
            boolean relevant = node.path("relevant").asBoolean(true);
            String next = node.path("new_query").asText("").strip();
            log.atInfo()
                    .addKeyValue("event", "web.relevance")
                    .addKeyValue("relevant", relevant)
                    .addKeyValue("new_query", next)
                    .log("Results {}", relevant ? "fit" : "do not fit, reformulating");
            if (relevant || next.isEmpty() || next.equalsIgnoreCase(query)) {
                return Optional.empty();
            }
            return Optional.of(next);
        } catch (ToolContext.BudgetExhausted e) {
            throw e;
        } catch (Exception e) {
            log.warn("Result evaluation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
