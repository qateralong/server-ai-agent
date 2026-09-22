package com.bebebe.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class BraveSearchProvider implements SearchProvider {

    static final String ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String endpoint;
    private final HttpClient http;
    private final Duration timeout;

    public BraveSearchProvider(String apiKey, Duration timeout) {
        this(apiKey, ENDPOINT, timeout);
    }

    BraveSearchProvider(String apiKey, String endpoint, Duration timeout) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public List<SearchResult> search(String query, int limit) {
        String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + Math.max(1, Math.min(limit, 20)) + "&search_lang=ru";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new WebSearchException("Brave responded HTTP " + response.statusCode()
                        + (response.statusCode() == 401 || response.statusCode() == 403
                                ? " -- check tools.web_search.brave_api_key" : ""));
            }
            return parse(response.body(), limit);
        } catch (IOException e) {
            throw new WebSearchException("Brave unavailable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("Search interrupted", e);
        }
    }

    static List<SearchResult> parse(String json, int limit) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode item : root.path("web").path("results")) {
                if (results.size() >= limit) {
                    break;
                }
                results.add(new SearchResult(
                        item.path("title").asText(""),
                        item.path("url").asText(""),
                        HtmlText.strip(item.path("description").asText(""))));
            }
            return results;
        } catch (IOException e) {
            throw new WebSearchException("Brave returned non-JSON", e);
        }
    }
}
