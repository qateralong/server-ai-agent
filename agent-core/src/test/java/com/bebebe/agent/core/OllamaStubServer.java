package com.bebebe.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

final class OllamaStubServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final LinkedBlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    OllamaStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(MAPPER.readTree(body));

            String content = replies.poll();
            if (content == null) {
                content = "{\"type\":\"reply\",\"reply\":\"no more replies\",\"python_code\":\"\"}";
            }
            String response = MAPPER.createObjectNode()
                    .put("model", "stub")
                    .put("done", true)
                    .set("message", MAPPER.createObjectNode()
                            .put("role", "assistant")
                            .put("content", content))
                    .toString();

            response = "{\"model\":\"stub\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":"
                    + MAPPER.writeValueAsString(content) + "}}";

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    void enqueue(String content) {
        replies.add(content);
    }

    void enqueueReply(String text) {
        enqueue("{\"type\":\"reply\",\"reply\":" + quote(text) + ",\"python_code\":\"\"}");
    }

    void enqueueScript(String code) {
        enqueue("{\"type\":\"run_script\",\"reply\":\"\",\"python_code\":" + quote(code) + "}");
    }

    void enqueuePlain(String text) {
        enqueue(text);
    }

    private static String quote(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<JsonNode> requests() {
        return requests;
    }

    int callCount() {
        return requests.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
