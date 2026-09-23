package com.bebebe.agent.telegram;

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

final class TelegramStubServer implements AutoCloseable {

    static final String TOKEN = "123456:TEST-TOKEN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final LinkedBlockingQueue<String> updates = new LinkedBlockingQueue<>();
    private final Map<String, List<JsonNode>> calls = new ConcurrentHashMap<>();
    private final AtomicInteger messageIds = new AtomicInteger(1000);

    /** What a voice-message download returns -- the content does not matter, only that it arrives. */
    static final byte[] VOICE_BYTES = "OggS fake voice message".getBytes(StandardCharsets.UTF_8);

    static final String VOICE_FILE_PATH = "voice/file_42.oga";

    /** What a document download returns; the path decides which of the two getFile answers. */
    static final byte[] DOCUMENT_BYTES = "Answer as a pirate.\nNever apologise.".getBytes(StandardCharsets.UTF_8);

    static final String DOCUMENT_FILE_PATH = "documents/file_7.txt";

    /** Set by a test that wants getFile to point at the document instead of the voice file. */
    volatile boolean serveDocument;

    /** A one-pixel PNG: the bytes do not matter, only that they arrive intact. */
    static final byte[] IMAGE_BYTES = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC");

    static final String IMAGE_FILE_PATH = "photos/file_9.png";

    volatile boolean serveImage;

    TelegramStubServer() throws IOException {
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
                case "getFile" -> """
                        {"ok":true,"result":{"file_id":"%s","file_path":"%s","file_size":%d}}"""
                        .formatted(request.path("file_id").asText(""),
                                serveImage ? IMAGE_FILE_PATH : serveDocument ? DOCUMENT_FILE_PATH : VOICE_FILE_PATH,
                                serveImage ? IMAGE_BYTES.length
                                        : serveDocument ? DOCUMENT_BYTES.length : VOICE_BYTES.length);
                case "sendMessage", "editMessageText" -> """
                        {"ok":true,"result":{"message_id":%d,"date":0,"chat":{"id":%s,"type":"private"}}}"""
                        .formatted(messageIds.incrementAndGet(), request.path("chat_id").asText("0"));
                default -> "{\"ok\":true,\"result\":true}";
            });
        });

        server.createContext("/file/bot" + TOKEN + "/", exchange -> {
            calls.computeIfAbsent("downloadFile", key -> new CopyOnWriteArrayList<>())
                    .add(MAPPER.createObjectNode().put("path", exchange.getRequestURI().getPath()));
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.endsWith(".png") ? IMAGE_BYTES
                    : path.endsWith(".txt") ? DOCUMENT_BYTES : VOICE_BYTES;
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
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

    void enqueue(String updateJson) {
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

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<JsonNode> calls(String method) {
        return calls.getOrDefault(method, List.of());
    }

    boolean awaitCalls(String method, int count, Duration timeout) {
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
