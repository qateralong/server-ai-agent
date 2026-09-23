package com.bebebe.agent.ollama;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public final class OllamaClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile OllamaConfig config;

    private final HttpClient http;

    public OllamaClient(OllamaConfig config, OllamaStats stats) {
        this(config);
        this.sharedStats = stats;
    }

    private volatile OllamaStats sharedStats;

    public OllamaClient(OllamaConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("Ollama client created: {}", config);
        if (config.requiresApiKey() && config.bearerToken().isEmpty()) {
            log.warn("{} requires api_key, but it is empty -- requests will return 401", config.baseUrl());
        }
    }

    public OllamaConfig config() {
        return config;
    }

    public void reconfigure(OllamaConfig updated) {
        this.config = updated;
        log.info("Ollama settings updated: {}", updated);
    }

    public void setModel(String model) {
        OllamaConfig current = config;
        if (current.model().equals(model)) {
            return;
        }
        reconfigure(new OllamaConfig(current.baseUrl(), current.apiKey(), model,
                current.timeout(), current.temperature(), current.numCtx()));
    }

    public void setApiKey(String apiKey) {
        OllamaConfig current = config;
        if (current.apiKey().equals(apiKey == null ? "" : apiKey.trim())) {
            return;
        }
        reconfigure(new OllamaConfig(current.baseUrl(), apiKey, current.model(),
                current.timeout(), current.temperature(), current.numCtx()));
    }

    public String ask(String prompt) {
        return chat(defaults(ChatRequest.builder(config.model()).user(prompt)).build()).text();
    }

    public String ask(String systemPrompt, String userPrompt) {
        return chat(defaults(ChatRequest.builder(config.model())
                .system(systemPrompt)
                .user(userPrompt)).build()).text();
    }

    public ChatResponse chat(ChatRequest request) {
        try {
            String body = writeJson(request, false);
            HttpResponse<String> response = send(
                    buildPost("/api/chat", body, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            ensureOk(response.statusCode(), response.body());
            ChatResponse parsed = readJson(response.body(), ChatResponse.class);
            stats().success(parsed);
            return parsed;
        } catch (RuntimeException e) {
            stats().failure(e.getMessage());
            throw e;
        }
    }

    private final OllamaStats stats = new OllamaStats();

    public OllamaStats stats() {
        return sharedStats != null ? sharedStats : stats;
    }

    public ChatResponse chatStream(ChatRequest request, Consumer<ChatResponse> onChunk) {
        String body = writeJson(request, true);
        HttpResponse<InputStream> response = send(
                buildPost("/api/chat", body, HttpResponse.BodyHandlers.ofInputStream()));

        if (response.statusCode() / 100 != 2) {
            ensureOk(response.statusCode(), drain(response.body()));
        }

        ChatResponse last = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                ChatResponse chunk = readJson(line, ChatResponse.class);
                last = chunk;
                onChunk.accept(chunk);
                if (chunk.done()) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new OllamaException("Ollama stream broke off", e);
        }

        if (last == null) {
            throw new OllamaException("Ollama returned an empty stream");
        }
        return last;
    }

    public List<String> listModels() {
        HttpResponse<String> response = send(
                buildGet("/api/tags", HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        ensureOk(response.statusCode(), response.body());
        try {
            var root = MAPPER.readTree(response.body());
            var models = root.path("models");
            return models.valueStream()
                    .map(n -> n.path("model").asText(n.path("name").asText("")))
                    .filter(s -> !s.isBlank())
                    .toList();
        } catch (IOException e) {
            throw new OllamaException("Failed to parse the /api/tags response", e);
        }
    }

    public String ping() {
        List<String> models = listModels();
        return "OK, models available: " + models.size();
    }

    /**
     * What the model can do, as Ollama itself reports it: {@code /api/show} answers with a
     * {@code capabilities} array -- {@code completion}, {@code tools}, {@code thinking},
     * {@code vision}. Asking is the only honest way to know: vision depends on the model, not on
     * the provider, and the same endpoint serves both kinds.
     *
     * @return the capabilities, or an empty list when they could not be obtained
     */
    public List<String> capabilities(String model) {
        try {
            HttpResponse<String> response = send(buildPost("/api/show",
                    MAPPER.createObjectNode().put("model", model).toString(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            if (response.statusCode() != 200) {
                log.debug("/api/show for «{}»: HTTP {}", model, response.statusCode());
                return List.of();
            }
            var node = MAPPER.readTree(response.body()).path("capabilities");
            return node.isArray() ? node.valueStream().map(n -> n.asText("")).toList() : List.of();
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot read the capabilities of «{}»: {}", model, e.getMessage());
            return List.of();
        }
    }

    private ChatRequest.Builder defaults(ChatRequest.Builder builder) {
        return builder
                .temperature(config.temperature())
                .numCtx(config.numCtx());
    }

    private HttpRequest.Builder baseRequest(String path) {

        OllamaConfig snapshot = config;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(snapshot.baseUrl() + path))
                .timeout(snapshot.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        snapshot.bearerToken().ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        return builder;
    }

    private <T> HttpRequestWithHandler<T> buildPost(String path, String body, HttpResponse.BodyHandler<T> handler) {
        return new HttpRequestWithHandler<>(
                baseRequest(path).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                handler);
    }

    private <T> HttpRequestWithHandler<T> buildGet(String path, HttpResponse.BodyHandler<T> handler) {
        return new HttpRequestWithHandler<>(baseRequest(path).GET().build(), handler);
    }

    private <T> HttpResponse<T> send(HttpRequestWithHandler<T> pair) {
        try {
            log.debug("-> {} {}", pair.request().method(), pair.request().uri());
            return http.send(pair.request(), pair.handler());
        } catch (IOException e) {
            throw new OllamaException("Network unavailable: " + pair.request().uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Ollama request interrupted", e);
        }
    }

    private void ensureOk(int status, String body) {
        if (status / 100 == 2) {
            return;
        }
        String hint = switch (status) {
            case 401, 403 -> " -- check ollama.api_key in the config (keys come from https://ollama.com/settings/keys)";
            case 404 -> " -- model not found; see the available ones via listModels()";
            case 429 -> " -- rate limit exceeded, try later";
            default -> "";
        };
        throw new OllamaException("Ollama returned HTTP " + status + hint + ": " + shorten(body), null, status);
    }

    private String writeJson(ChatRequest request, boolean stream) {
        try {
            ObjectNode node = MAPPER.valueToTree(request);
            node.put("stream", stream);
            return MAPPER.writeValueAsString(node);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new OllamaException("Failed to serialise the request", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new OllamaException("Failed to parse the Ollama response: " + shorten(json), e);
        }
    }

    private static String drain(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<response body could not be read>";
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "<empty>";
        }
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    @Override
    public void close() {
        http.close();
    }

    private record HttpRequestWithHandler<T>(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    }
}
