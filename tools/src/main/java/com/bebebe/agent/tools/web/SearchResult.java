package com.bebebe.agent.tools.web;

public record SearchResult(String title, String url, String snippet) {

    public SearchResult {
        title = title == null ? "" : title.strip();
        url = url == null ? "" : url.strip();
        snippet = snippet == null ? "" : snippet.strip();
    }

    public String describe(int index) {
        return "%d. %s\n   %s\n   %s".formatted(index, title, url, snippet);
    }
}
