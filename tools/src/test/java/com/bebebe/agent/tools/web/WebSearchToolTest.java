package com.bebebe.agent.tools.web;

import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    private HttpServer pages;
    private String pageUrl;

    private final List<String> llmScript = new ArrayList<>();
    private final List<String> llmPrompts = new ArrayList<>();
    private final AtomicInteger llmCalls = new AtomicInteger();

    private final List<List<SearchResult>> searchScript = new ArrayList<>();
    private final List<String> searchedQueries = new ArrayList<>();

    private final ToolContext.LlmCaller llm = (system, user, schema) -> {
        llmCalls.incrementAndGet();
        llmPrompts.add(user);
        if (llmScript.isEmpty()) {
            throw new ToolContext.BudgetExhausted("budget exhausted");
        }
        return llmScript.removeFirst();
    };

    private final SearchProvider provider = new SearchProvider() {
        public String name() { return "fake"; }
        public List<SearchResult> search(String query, int limit) {
            searchedQueries.add(query);
            if (searchScript.isEmpty()) {
                return List.of();
            }
            return searchScript.removeFirst();
        }
    };

    @BeforeEach
    void startPages() throws IOException {
        pages = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pages.createContext("/rate", exchange -> {
            byte[] body = ("<html><head><title>Курс</title><script>x()</script></head><body>"
                    + "<nav>меню</nav><h1>Курс доллара</h1><p>Официальный курс ЦБ РФ на 19 сентября: "
                    + "<b>95,42</b> рубля за доллар.</p>" + "<p>lorem ipsum ".repeat(30) + "</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        pages.createContext("/pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write("%PD".getBytes());
            exchange.close();
        });
        pages.start();
        pageUrl = "http://127.0.0.1:" + pages.getAddress().getPort();
    }

    @AfterEach
    void stopPages() {
        pages.stop(0);
    }

    private WebSearchTool tool(int maxReformulations, int fetchPages) {
        WebSearchConfig config = new WebSearchConfig("auto", "", 5, fetchPages, 2000, maxReformulations, Duration.ofSeconds(5), null);
        return new WebSearchTool(config, provider, new PageFetcher(Duration.ofSeconds(5), 2000));
    }

    private ToolResult run(WebSearchTool tool, String question, String topic) {
        return tool.execute(Map.of("query", topic), new ToolContext(llm, question));
    }

    private SearchResult rate() {
        return new SearchResult("Курс доллара ЦБ", pageUrl + "/rate", "Официальный курс 95,42");
    }

    @Test
    void formulatesShortQueryInsteadOfRawText() {
        llmScript.add("курс доллара ЦБ РФ");
        llmScript.add("{\"relevant\":true,\"new_query\":\"\"}");
        searchScript.add(List.of(rate()));

        run(tool(2, 1), "слушай, а не подскажешь, почём сегодня доллар по курсу центробанка?", "курс доллара");

        assertEquals(List.of("курс доллара ЦБ РФ"), searchedQueries);
        assertTrue(llmPrompts.getFirst().contains("почём сегодня доллар"), "the model must see the original question");
    }

    @Test
    void readsPageNotOnlySnippet() {
        llmScript.add("курс доллара");
        llmScript.add("{\"relevant\":true,\"new_query\":\"\"}");
        searchScript.add(List.of(rate()));

        ToolResult result = run(tool(2, 1), "курс доллара?", "курс доллара");

        assertTrue(result.success());
        assertTrue(result.content().contains("95,42 рубля за доллар"), "page text must be in the result");
        assertFalse(result.content().contains("x()"), "scripts stripped");
        assertFalse(result.content().contains("меню"), "navigation stripped");
        assertTrue(result.sources().contains("/rate"));
    }

    @Test
    void reformulatesWhenResultsOffTopic() {
        llmScript.add("погода");
        llmScript.add("{\"relevant\":false,\"new_query\":\"погода Казань сегодня\"}");
        llmScript.add("{\"relevant\":true,\"new_query\":\"\"}");
        searchScript.add(List.of(new SearchResult("Погода в мире", "https://x", "общее")));
        searchScript.add(List.of(rate()));

        ToolResult result = run(tool(2, 0), "какая погода в Казани?", "погода");

        assertEquals(List.of("погода", "погода Казань сегодня"), searchedQueries);
        assertTrue(result.success());
        assertTrue(result.content().contains("Query: погода Казань сегодня"));
    }

    @Test
    void reformulationsAreLimitedBySetting() {
        llmScript.add("q0");
        llmScript.add("{\"relevant\":false,\"new_query\":\"q1\"}");
        llmScript.add("{\"relevant\":false,\"new_query\":\"q2\"}");
        llmScript.add("{\"relevant\":false,\"new_query\":\"q3\"}");
        for (int i = 0; i < 5; i++) {
            searchScript.add(List.of(new SearchResult("miss", "https://x" + i, "miss")));
        }

        run(tool(1, 0), "question", "topic");

        assertEquals(List.of("q0", "q1"), searchedQueries, "one reformulation -- two searches");
    }

    @Test
    void budgetExhaustedInsideToolPropagates() {

        llmScript.add("курс");
        searchScript.add(List.of(rate()));

        assertThrows(ToolContext.BudgetExhausted.class, () -> run(tool(2, 1), "курс", "курс"));
    }

    @Test
    void emptyResultsIsFailureWithClearText() {
        llmScript.add("нечто уникальное");
        llmScript.add("{\"relevant\":false,\"new_query\":\"\"}");

        ToolResult result = run(tool(2, 1), "найди нечто", "нечто");

        assertFalse(result.success());
        assertTrue(result.content().contains("Nothing found"), result.content());
    }

    @Test
    void unavailableSearchEngineIsFailureNotCrash() {
        SearchProvider broken = new SearchProvider() {
            public String name() { return "broken"; }
            public List<SearchResult> search(String q, int l) { throw new WebSearchException("no network"); }
        };
        llmScript.add("q");
        WebSearchTool tool = new WebSearchTool(WebSearchConfig.defaults(), broken, new PageFetcher(Duration.ofSeconds(5), 2000));

        ToolResult result = tool.execute(Map.of("query", "q"), new ToolContext(llm, "q"));

        assertFalse(result.success());
        assertTrue(result.content().contains("no network"));
    }

    @Test
    void nonHtmlPagesAreSkipped() {
        llmScript.add("q");
        llmScript.add("{\"relevant\":true,\"new_query\":\"\"}");
        searchScript.add(List.of(
                new SearchResult("PDF", pageUrl + "/pdf", "документ"),
                rate()));

        ToolResult result = run(tool(2, 1), "q", "q");

        assertTrue(result.content().contains("95,42"), "the next, HTML page must be opened");
        assertFalse(result.sources().contains("/pdf"));
    }

    @Test
    void withoutPagesSourcesComeFromResults() {
        llmScript.add("q");
        llmScript.add("{\"relevant\":true,\"new_query\":\"\"}");
        searchScript.add(List.of(new SearchResult("A", "https://a.example", "a")));

        ToolResult result = run(tool(2, 0), "q", "q");

        assertTrue(result.sources().contains("https://a.example"));
    }

    @Test
    void providerIsChosenByKey() {
        assertEquals("duckduckgo", WebSearchConfig.defaults().buildProvider().name());
        assertEquals("brave", new WebSearchConfig("auto", "key", 5, 1, 2000, 1, Duration.ofSeconds(5), null)
                .buildProvider().name());
        assertEquals("duckduckgo", new WebSearchConfig("duckduckgo", "key", 5, 1, 2000, 1, Duration.ofSeconds(5), null)
                .buildProvider().name());
        assertThrows(IllegalArgumentException.class,
                () -> new WebSearchConfig("brave", "", 5, 1, 2000, 1, Duration.ofSeconds(5), null).buildProvider());
    }
}
