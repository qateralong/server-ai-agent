package com.bebebe.agent.tools.web;

import com.bebebe.agent.config.ConfigSection;

import java.time.Duration;

public record WebSearchConfig(
        String provider,
        String braveApiKey,
        int maxResults,
        int fetchPages,
        int maxPageChars,
        int maxReformulations,
        Duration timeout,
        String ddgEndpoint,
        Duration cacheFor
) {

    public static final String SECTION = "tools.web_search";

    public WebSearchConfig {
        provider = provider == null || provider.isBlank() ? "auto" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        braveApiKey = braveApiKey == null ? "" : braveApiKey.strip();
        ddgEndpoint = ddgEndpoint == null || ddgEndpoint.isBlank() ? DuckDuckGoProvider.ENDPOINT : ddgEndpoint.strip();
        cacheFor = cacheFor == null ? Duration.ZERO : cacheFor;
        if (maxResults < 1 || fetchPages < 0 || maxPageChars < 200 || maxReformulations < 0) {
            throw new IllegalArgumentException("tools.web_search: invalid limits");
        }
        if (cacheFor.isNegative()) {
            throw new IllegalArgumentException("tools.web_search.cache_minutes must be >= 0");
        }
    }

    public static WebSearchConfig from(ConfigSection section) {
        return new WebSearchConfig(
                section.string("provider", "auto"),
                section.string("brave_api_key", ""),
                section.integer("max_results", 6),
                section.integer("fetch_pages", 2),
                section.integer("max_page_chars", 4000),
                section.integer("max_reformulations", 2),
                section.seconds("timeout_seconds", Duration.ofSeconds(15)),

                section.string("ddg_endpoint", DuckDuckGoProvider.ENDPOINT),
                Duration.ofMinutes(section.longValue("cache_minutes").orElse(10L)));
    }

    public static WebSearchConfig defaults() {
        return new WebSearchConfig("auto", "", 6, 2, 4000, 2, Duration.ofSeconds(15),
                DuckDuckGoProvider.ENDPOINT, Duration.ofMinutes(10));
    }

    public SearchProvider buildProvider() {
        boolean haveKey = !braveApiKey.isEmpty();
        return switch (provider) {
            case "brave" -> {
                if (!haveKey) {
                    throw new IllegalArgumentException("tools.web_search.provider = brave, but brave_api_key is empty");
                }
                yield new BraveSearchProvider(braveApiKey, timeout);
            }
            case "duckduckgo", "ddg" -> new DuckDuckGoProvider(ddgEndpoint, timeout);
            default -> haveKey ? new BraveSearchProvider(braveApiKey, timeout) : new DuckDuckGoProvider(ddgEndpoint, timeout);
        };
    }
}
