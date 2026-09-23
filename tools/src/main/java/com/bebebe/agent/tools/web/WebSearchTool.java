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
    private final Cache cache = new Cache();

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

        String key = cacheKey(question, topic);
        if (!config.cacheFor().isZero()) {
            Optional<ToolResult> cached = cache.get(key);
            if (cached.isPresent()) {
                log.atInfo().addKeyValue("event", "web.cache_hit").addKeyValue("question", question)
                        .log("The same thing was already searched for recently -- reusing the result");
                return cached.get();
            }
        }

        String query = formulateQuery(context, question, topic);
        List<SearchResult> results = List.of();
        String lastError = null;
        Verdict verdict = Verdict.unjudged();

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

            verdict = results.isEmpty()
                    ? new Verdict(false, false, "", true)
                    : judge(context, question, query, results);

            boolean canRetry = attempt < config.maxReformulations();
            if (verdict.relevant() || !canRetry
                    || verdict.nextQuery().isEmpty() || verdict.nextQuery().equalsIgnoreCase(query)) {
                break;
            }
            query = verdict.nextQuery();
        }

        if (results.isEmpty()) {
            return ToolResult.failure(lastError != null
                    ? "Search unavailable: " + lastError
                    : "Nothing found for «" + query + "»");
        }

        // The results were looked at and judged not to answer the question. Handing them over
        // anyway is how a search failure turns into a confident wrong answer.
        if (verdict.judged() && !verdict.relevant()) {
            log.atInfo().addKeyValue("event", "web.irrelevant").addKeyValue("query", query)
                    .log("Found pages do not answer the question -- reporting a failure instead");
            return ToolResult.failure("""
                    Could not find an answer to this question. The search for «%s» returned pages \
                    about something else, and the attempts to reword it did not help.
                    Tell the user plainly that you could not find current information on this, \
                    and do not replace it with a guess of your own."""
                    .formatted(query));
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

        if (verdict.conflicting()) {

            content.append("\nNOTE: the sources disagree with each other here. Say so and give "
                    + "the variants with their sources; do not pick one and present it as the fact.\n");
        }

        ToolResult result = ToolResult.ok(content.toString(), String.join("\n", sources));
        if (!config.cacheFor().isZero()) {
            cache.put(key, result);
        }
        return result;
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

    /**
     * What the model thinks of the results it was shown.
     *
     * @param relevant    do the snippets actually answer the question
     * @param conflicting do the sources disagree with each other about the answer
     * @param nextQuery   a different wording to try, empty when there is none
     * @param judged      false when the verdict could not be obtained at all
     */
    record Verdict(boolean relevant, boolean conflicting, String nextQuery, boolean judged) {

        static Verdict unjudged() {
            return new Verdict(true, false, "", false);
        }
    }

    private Verdict judge(ToolContext context, String question, String query, List<SearchResult> results) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "relevant", Map.of("type", "boolean"),
                "conflicting", Map.of("type", "boolean"),
                "new_query", Map.of("type", "string")));
        schema.put("required", List.of("relevant", "conflicting", "new_query"));
        schema.put("additionalProperties", false);

        StringBuilder sb = new StringBuilder("User question: ").append(question)
                .append("\nSearch query: ").append(query).append("\n\nResults:\n");
        if (results.isEmpty()) {
            sb.append("(empty)\n");
        }
        for (int i = 0; i < results.size(); i++) {
            sb.append(results.get(i).describe(i + 1)).append('\n');
        }
        sb.append("""

                relevant    -- true only if these snippets really answer THAT question. A page
                               about roughly the same subject, or about another date, place or
                               version, is not an answer: then false.
                conflicting -- true if the sources give different answers to the same question
                               (different figures, dates, outcomes).
                new_query   -- when relevant = false: a different wording (other words, a place
                               or date refinement, another language). Otherwise "".""");

        try {
            String answer = context.llm().ask(
                    "You evaluate search results. Answer only with JSON per the schema.", sb.toString(), schema);
            JsonNode node = MAPPER.readTree(answer);
            Verdict verdict = new Verdict(
                    node.path("relevant").asBoolean(true),
                    node.path("conflicting").asBoolean(false),
                    node.path("new_query").asText("").strip(),
                    true);
            log.atInfo()
                    .addKeyValue("event", "web.relevance")
                    .addKeyValue("relevant", verdict.relevant())
                    .addKeyValue("conflicting", verdict.conflicting())
                    .addKeyValue("new_query", verdict.nextQuery())
                    .log("Results {}", verdict.relevant() ? "fit" : "do not fit");
            return verdict;
        } catch (ToolContext.BudgetExhausted e) {
            throw e;
        } catch (Exception e) {

            // Without a verdict the honest thing is to pass the results on rather than to
            // declare a failure: the snippets may well be fine.
            log.warn("Result evaluation failed: {}", e.getMessage());
            return Verdict.unjudged();
        }
    }

    /**
     * Repeating the same question a minute later should not cost another three model calls and
     * another round-trip to the search engine. Keyed by the stems of the question, so "какой
     * сейчас курс доллара" and "курс доллара сейчас какой" are one entry.
     */
    private final class Cache {

        private record Entry(ToolResult result, java.time.Instant until) { }

        private final Map<String, Entry> entries = new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > 32;
            }
        };

        synchronized Optional<ToolResult> get(String key) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (java.time.Instant.now().isAfter(entry.until())) {
                entries.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.result());
        }

        synchronized void put(String key, ToolResult result) {
            entries.put(key, new Entry(result, java.time.Instant.now().plus(config.cacheFor())));
        }
    }

    /** Words of four letters or more, cut to a stem and sorted -- word order must not matter. */
    static String cacheKey(String question, String topic) {
        String text = (question + " " + topic).toLowerCase(java.util.Locale.ROOT);
        return new java.util.TreeSet<>(
                java.util.Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                        .filter(w -> w.length() >= 4)
                        .map(w -> w.length() <= 5 ? w.substring(0, w.length() - 1) : w.substring(0, 4))
                        .toList())
                .toString();
    }
}
