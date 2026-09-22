package com.bebebe.agent.ollama;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private OllamaClient client;

    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.close();
        }
        server.stop(0);
    }

    private OllamaClient clientFor(String apiKey) {
        OllamaConfig config = new OllamaConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                apiKey,
                "test-model",
                Duration.ofSeconds(10),
                0.5,
                2048);
        client = new OllamaClient(config);
        return client;
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, status, body);
        });
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void parsesChatResponse() throws IOException {
        respond("/api/chat", 200, """
                {
                  "model": "test-model",
                  "created_at": "2026-09-18T10:00:00Z",
                  "message": {"role": "assistant", "content": "Небо голубое из-за рассеяния."},
                  "done": true,
                  "done_reason": "stop",
                  "eval_count": 20,
                  "eval_duration": 1000000000
                }
                """);

        ChatResponse response = clientFor("").chat(
                ChatRequest.builder("test-model").user("Почему небо голубое?").build());

        assertEquals("Небо голубое из-за рассеяния.", response.text());
        assertEquals("stop", response.doneReason());
        assertTrue(response.done());
        assertEquals(20.0, response.tokensPerSecond());
    }

    @Test
    void sendsCorrectRequestBody() throws IOException {
        respond("/api/chat", 200, """
                {"model":"test-model","message":{"role":"assistant","content":"ок"},"done":true}
                """);

        clientFor("").chat(ChatRequest.builder("test-model")
                .system("Ты краток.")
                .user("Привет")
                .temperature(0.5)
                .numCtx(2048)
                .build());

        JsonNode sent = MAPPER.readTree(lastBody.get());
        assertEquals("test-model", sent.get("model").asText());

        assertTrue(sent.has("stream"));
        assertEquals(false, sent.get("stream").asBoolean());
        assertEquals(2, sent.get("messages").size());
        assertEquals("system", sent.get("messages").get(0).get("role").asText());
        assertEquals("Привет", sent.get("messages").get(1).get("content").asText());
        assertEquals(0.5, sent.get("options").get("temperature").asDouble());
        assertEquals(2048, sent.get("options").get("num_ctx").asInt());
    }

    @Test
    void sendsBearerOnlyWhenKeySet() throws IOException {
        respond("/api/chat", 200, """
                {"model":"m","message":{"role":"assistant","content":"ок"},"done":true}
                """);

        clientFor("secret-key-123").ask("привет");
        assertEquals("Bearer secret-key-123", lastAuth.get());

        client.close();
        lastAuth.set(null);
        clientFor("").ask("привет");
        assertNull(lastAuth.get());
    }

    @Test
    void assemblesStreamFromChunks() {
        server.createContext("/api/chat", exchange -> send(exchange, 200, String.join("\n",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Небо \"},\"done\":false}",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"голубое\"},\"done\":false}",
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                        + "\"done_reason\":\"stop\",\"eval_count\":3,\"eval_duration\":1000000000}")));

        List<String> chunks = new ArrayList<>();
        ChatResponse last = clientFor("").chatStream(
                ChatRequest.builder("m").user("вопрос").build(),
                chunk -> chunks.add(chunk.text()));

        assertEquals(List.of("Небо ", "голубое", ""), chunks);
        assertEquals("Небо голубое", String.join("", chunks));
        assertTrue(last.done());
        assertEquals("stop", last.doneReason());
    }

    @Test
    void streamGoesWithStreamTrueFlag() throws IOException {
        respond("/api/chat", 200,
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"done\":true}");

        clientFor("").chatStream(ChatRequest.builder("m").user("q").build(), chunk -> { });

        assertTrue(MAPPER.readTree(lastBody.get()).get("stream").asBoolean());
    }

    @Test
    void readsModelList() {
        respond("/api/tags", 200, """
                {"models":[
                  {"name":"gpt-oss:120b","model":"gpt-oss:120b"},
                  {"name":"qwen3.5:cloud","model":"qwen3.5:cloud"}
                ]}
                """);

        assertEquals(List.of("gpt-oss:120b", "qwen3.5:cloud"), clientFor("k").listModels());
    }

    @Test
    void hintsAboutKeyOn401() {
        respond("/api/chat", 401, "{\"error\":\"unauthorized\"}");

        OllamaException e = assertThrows(OllamaException.class, () -> clientFor("bad-key").ask("привет"));

        assertEquals(401, e.httpStatus());
        assertTrue(e.getMessage().contains("api_key"), e.getMessage());
    }

    @Test
    void hintsAboutModelOn404() {
        respond("/api/chat", 404, "{\"error\":\"model not found\"}");

        OllamaException e = assertThrows(OllamaException.class, () -> clientFor("k").ask("привет"));

        assertEquals(404, e.httpStatus());
        assertTrue(e.getMessage().contains("model"), e.getMessage());
    }

    @Test
    void reportsUnavailableNetwork() {
        OllamaConfig config = new OllamaConfig(
                "http://127.0.0.1:1", "", "m", Duration.ofSeconds(2), null, null);
        try (OllamaClient offline = new OllamaClient(config)) {
            OllamaException e = assertThrows(OllamaException.class, () -> offline.ask("привет"));
            assertTrue(e.getMessage().contains("Network unavailable"), e.getMessage());
        }
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void statsCountCallsTokensFailuresAndSurviveRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) {
        respond("/api/chat", 200, """
                {"model":"m","message":{"role":"assistant","content":"ок"},"done":true,
                 "prompt_eval_count": 30, "eval_count": 12}
                """);
        OllamaClient client = clientFor("");
        java.nio.file.Path stats = temp.resolve("ollama-stats.json");
        client.stats().persistTo(stats);

        client.chat(ChatRequest.builder("m").user("q").build());
        client.chat(ChatRequest.builder("m").user("q").build());
        OllamaStats.Snapshot s = client.stats().snapshot();
        assertEquals(2, s.sessionCalls());
        assertEquals(84, s.sessionTokens());
        assertTrue(s.available());
        assertTrue(s.lastSuccess().isPresent());

        server.removeContext("/api/chat");
        respond("/api/chat", 500, "{\"error\":\"boom\"}");
        assertThrows(OllamaException.class, () -> client.chat(ChatRequest.builder("m").user("q").build()));
        s = client.stats().snapshot();
        assertEquals(1, s.sessionFailures());
        assertFalse(s.available(), "last outcome is a failure");
        assertTrue(s.lastError().contains("500"), s.lastError());

        OllamaStats fresh = new OllamaStats();
        fresh.persistTo(stats);
        assertEquals(3, fresh.snapshot().totalCalls());
        assertEquals(84, fresh.snapshot().totalTokens());
        assertEquals(0, fresh.snapshot().sessionCalls());
    }
}
