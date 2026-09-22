package com.bebebe.agent.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TelegramStubServer implements AutoCloseable {

    public static final String TOKEN = "123456:TEST-TOKEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final LinkedBlockingQueue<String> updates = new LinkedBlockingQueue<>();
    private final Map<String, List<JsonNode>> calls = new ConcurrentHashMap<>();
    private final AtomicInteger messageIds = new AtomicInteger(1000);

    public TelegramStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot" + TOKEN + "/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = path.substring(path.lastIndexOf('/') + 1);
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String contentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
            JsonNode request;
            if (contentType.startsWith("multipart/form-data")) {

                request = parseMultipart(raw, contentType);
            } else {
                String body = new String(raw, StandardCharsets.UTF_8);
                request = body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
            }
            calls.computeIfAbsent(method, key -> new CopyOnWriteArrayList<>()).add(request);

            respond(exchange, switch (method) {
                case "getMe" -> """
                        {"ok":true,"result":{"id":1,"is_bot":true,"first_name":"Тест","username":"test_bot"}}""";
                case "getUpdates" -> updatesResponse();
                case "sendMessage", "editMessageText" -> """
                        {"ok":true,"result":{"message_id":%d,"date":0,"chat":{"id":%s,"type":"private"}}}"""
                        .formatted(messageIds.incrementAndGet(), request.path("chat_id").asText("0"));
                default -> "{\"ok\":true,\"result\":true}";
            });
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    private static JsonNode parseMultipart(byte[] raw, String contentType) {
        String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
        String text = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String part : text.split(java.util.regex.Pattern.quote(boundary))) {
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String value = part.substring(headerEnd + 4);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            }
            java.util.regex.Matcher name = java.util.regex.Pattern.compile("name=\"([^\"]+)\"").matcher(headers);
            if (!name.find()) {
                continue;
            }
            if (headers.contains("filename=")) {
                node.put(name.group(1) + "_bytes", value.length());
                java.util.regex.Matcher ct = java.util.regex.Pattern.compile("Content-Type: (\\S+)").matcher(headers);
                node.put(name.group(1) + "_type", ct.find() ? ct.group(1) : "");
            } else {
                node.put(name.group(1), new String(value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8));
            }
        }
        return node;
    }

    public void enqueue(String updateJson) {
        updates.add(updateJson);
    }

    private String updatesResponse() {
        try {
            String first = updates.poll(250, TimeUnit.MILLISECONDS);
            if (first == null) {
                return "{\"ok\":true,\"result\":[]}";
            }
            StringBuilder batch = new StringBuilder(first);
            String next;
            while ((next = updates.poll()) != null) {
                batch.append(',').append(next);
            }
            return "{\"ok\":true,\"result\":[" + batch + "]}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"ok\":true,\"result\":[]}";
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<JsonNode> calls(String method) {
        return calls.getOrDefault(method, List.of());
    }

    public boolean awaitCalls(String method, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (calls(method).size() >= count) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
