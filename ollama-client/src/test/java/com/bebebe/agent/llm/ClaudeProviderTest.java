package com.bebebe.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[{"type":"text","text":"{\\"type\\":\\"reply\\",\\"reply\\":\\"Лиссабон\\"}"}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":42,"output_tokens":7}}""";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            bodies.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            headers.add(Map.of(
                    "x-api-key", String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")),
                    "anthropic-version", String.valueOf(ex.getRequestHeaders().getFirst("anthropic-version"))));
            respond(ex, status, status == 200 ? reply
                    : "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        });
        server.createContext("/v1/models", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, """
                {"data":[{"id":"claude-opus-5","type":"model","display_name":"Claude Opus 5","created_at":"2026-01-01T00:00:00Z"},
                         {"id":"claude-sonnet-5","type":"model","display_name":"Claude Sonnet 5","created_at":"2026-01-01T00:00:00Z"}],
                 "has_more":false,"first_id":"claude-opus-5","last_id":"claude-sonnet-5"}""");
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ClaudeProvider provider(String key) {
        return new ClaudeProvider(ClaudeConfig.defaults(), key, "claude-opus-5",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void requestCarriesKeyVersionSystemAndSchema() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("type", Map.of("type", "string"), "reply", Map.of("type", "string")),
                "required", List.of("type"), "additionalProperties", false);
        LlmRequest request = new LlmRequest("Ты агент.", List.of(
                LlmRequest.LlmMessage.user("привет"), LlmRequest.LlmMessage.assistant("привет!")),
                "столица Португалии?", 0.2, schema);

        LlmResponse response = provider("sk-test").chat(request);

        assertEquals("{\"type\":\"reply\",\"reply\":\"Лиссабон\"}", response.text());
        assertEquals(42, response.inputTokens());
        assertEquals(7, response.outputTokens());
        assertEquals("claude-opus-5", response.model());
        assertEquals("sk-test", headers.getFirst().get("x-api-key"));
        assertFalse(headers.getFirst().get("anthropic-version").equals("null"), "the SDK sets anthropic-version");
        JsonNode body = bodies.getFirst();
        assertEquals("claude-opus-5", body.path("model").asText());
        assertTrue(body.path("system").toString().contains("Ты агент."), body.toString());
        assertEquals(3, body.path("messages").size());
        assertEquals("user", body.path("messages").get(0).path("role").asText());
        assertEquals("assistant", body.path("messages").get(1).path("role").asText());
        assertEquals("user", body.path("messages").get(2).path("role").asText());
        assertTrue(body.path("output_config").path("format").path("schema").path("properties").has("reply"),
                "decision schema goes into structured outputs: " + body.path("output_config"));
        assertEquals(0.2, body.path("temperature").asDouble(), 1e-9);
    }

    @Test
    void historyIsNormalisedToAlternatingRoles() {
        List<LlmRequest.LlmMessage> out = ClaudeProvider.alternate(List.of(
                LlmRequest.LlmMessage.assistant("лишний первый"),
                LlmRequest.LlmMessage.user("раз"),
                LlmRequest.LlmMessage.user("два"),
                LlmRequest.LlmMessage.assistant(""),
                LlmRequest.LlmMessage.assistant("ответ")), "три");

        assertEquals(List.of("user", "assistant", "user"), out.stream().map(LlmRequest.LlmMessage::role).toList());
        assertEquals("раз\n\nдва", out.get(0).text());
        assertEquals("три", out.get(2).text());
    }

    @Test
    void authErrorIsLlmExceptionWithHintAndFailureInStats() {
        status = 401;
        ClaudeProvider p = provider("bad");

        LlmException e = assertThrows(LlmException.class, () -> p.chat(new LlmRequest("", List.of(), "q", null, null)));

        assertEquals(401, e.httpStatus());
        assertTrue(e.getMessage().contains("invalid Claude API key"), e.getMessage());
        assertEquals(1, p.stats().snapshot().sessionFailures());
        assertFalse(p.stats().snapshot().available());
    }

    @Test
    void withoutKeyNoNetworkAtAll() {
        LlmException e = assertThrows(LlmException.class,
                () -> provider("").chat(new LlmRequest("", List.of(), "q", null, null)));

        assertTrue(e.getMessage().contains("API key is not set"), e.getMessage());
        assertTrue(bodies.isEmpty());
    }

    @Test
    void modelListAndPing() {
        ClaudeProvider p = provider("sk-test");

        assertEquals(List.of("claude-opus-5", "claude-sonnet-5"), p.listModels());
        assertTrue(p.ping());
        assertEquals("Claude (Anthropic)", p.displayName());
        assertEquals(Duration.ofSeconds(120), p.timeout());
    }
}
