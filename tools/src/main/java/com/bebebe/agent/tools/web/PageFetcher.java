package com.bebebe.agent.tools.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

public final class PageFetcher {

    private static final Logger log = LoggerFactory.getLogger(PageFetcher.class);

    static final int MAX_BODY_BYTES = 1_500_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";

    private final HttpClient http;
    private final Duration timeout;
    private final int maxChars;

    public PageFetcher(Duration timeout, int maxChars) {
        this.timeout = timeout;
        this.maxChars = maxChars;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Optional<String> fetchText(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return Optional.empty();
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "ru,en;q=0.8")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.debug("Page {} -> HTTP {}", url, response.statusCode());
                return Optional.empty();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("text/html");
            if (!contentType.contains("html") && !contentType.contains("text/plain")) {
                log.debug("Page {} is not HTML ({}), skipping", url, contentType);
                return Optional.empty();
            }
            byte[] body = response.body();
            if (body.length > MAX_BODY_BYTES) {
                body = java.util.Arrays.copyOf(body, MAX_BODY_BYTES);
            }
            String text = HtmlText.extract(new String(body, charsetOf(contentType, body)));
            if (text.length() < 200) {
                return Optional.empty();
            }
            return Optional.of(text.length() > maxChars ? text.substring(0, maxChars) + "…" : text);
        } catch (IOException e) {
            log.debug("Page {} unavailable: {}", url, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static java.nio.charset.Charset charsetOf(String contentType, byte[] body) {
        java.util.regex.Matcher header = java.util.regex.Pattern.compile("(?i)charset=([\\w-]+)").matcher(contentType);
        if (header.find()) {
            try {
                return java.nio.charset.Charset.forName(header.group(1));
            } catch (RuntimeException ignored) {

            }
        }
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher meta = java.util.regex.Pattern.compile("(?i)charset=[\"']?([\\w-]+)").matcher(head);
        if (meta.find()) {
            try {
                return java.nio.charset.Charset.forName(meta.group(1));
            } catch (RuntimeException ignored) {

            }
        }
        return StandardCharsets.UTF_8;
    }
}
