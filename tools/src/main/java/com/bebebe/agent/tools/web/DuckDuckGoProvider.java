package com.bebebe.agent.tools.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DuckDuckGoProvider implements SearchProvider {

    public static final String ENDPOINT = "https://html.duckduckgo.com/html/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";

    private static final Pattern LINK = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern SNIPPET = Pattern.compile(
            "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern UDDG = Pattern.compile("uddg=([^&]+)");

    private final String endpoint;
    private final HttpClient http;
    private final Duration timeout;

    public DuckDuckGoProvider(Duration timeout) {
        this(ENDPOINT, timeout);
    }

    public DuckDuckGoProvider(String endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&kl=ru-ru";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .header("Accept-Language", "ru,en;q=0.8")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 202) {
                throw new WebSearchException("DuckDuckGo showed an anti-bot page (HTTP 202)");
            }
            if (response.statusCode() / 100 != 2) {
                throw new WebSearchException("DuckDuckGo responded HTTP " + response.statusCode());
            }
            return parse(response.body(), limit);
        } catch (IOException e) {
            throw new WebSearchException("DuckDuckGo unavailable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("Search interrupted", e);
        }
    }

    static List<SearchResult> parse(String html, int limit) {
        List<String[]> links = new ArrayList<>();
        Matcher link = LINK.matcher(html);
        while (link.find()) {
            links.add(new String[]{unwrap(link.group(1)), HtmlText.strip(link.group(2))});
        }
        List<String> snippets = new ArrayList<>();
        Matcher snippet = SNIPPET.matcher(html);
        while (snippet.find()) {
            snippets.add(HtmlText.strip(snippet.group(1)));
        }

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < links.size() && results.size() < limit; i++) {
            String url = links.get(i)[0];
            if (url.isEmpty() || url.contains("duckduckgo.com/y.js")) {
                continue;
            }
            results.add(new SearchResult(links.get(i)[1], url, i < snippets.size() ? snippets.get(i) : ""));
        }
        return results;
    }

    static String unwrap(String href) {
        Matcher m = UDDG.matcher(href);
        if (m.find()) {
            return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        return href;
    }
}
