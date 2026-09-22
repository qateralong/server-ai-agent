package com.bebebe.agent.telegram.api;

import com.bebebe.agent.telegram.api.Dto.CallbackQuery;
import com.bebebe.agent.telegram.api.Dto.InlineKeyboardMarkup;
import com.bebebe.agent.telegram.api.Dto.Message;
import com.bebebe.agent.telegram.api.Dto.Update;
import com.bebebe.agent.telegram.api.Dto.User;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class TelegramApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TelegramApi.class);

    private static final String BASE_URL = "https://api.telegram.org";

    public static final int MAX_MESSAGE_LENGTH = 4096;

    private static final Duration POLL_SLACK = Duration.ofSeconds(20);

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String apiRoot;
    private final String fileRoot;
    private final HttpClient http;

    public TelegramApi(String botToken) {
        this(botToken, BASE_URL);
    }

    public TelegramApi(String botToken, String baseUrl) {
        this.apiRoot = baseUrl + "/bot" + botToken + "/";

        this.fileRoot = baseUrl + "/file/bot" + botToken + "/";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public User getMe() {
        return call("getMe", mapper.createObjectNode(), Duration.ofSeconds(30), User.class);
    }

    public List<Update> getUpdates(long offset, Duration timeout) {
        ObjectNode body = mapper.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", timeout.toSeconds());

        body.putArray("allowed_updates").add("message").add("callback_query");

        JavaType listOfUpdates = mapper.getTypeFactory()
                .constructCollectionType(List.class, Update.class);
        return call("getUpdates", body, timeout.plus(POLL_SLACK), listOfUpdates);
    }

    public Message sendMessage(long chatId, String text, InlineKeyboardMarkup markup) {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", truncate(text));
        body.put("parse_mode", "HTML");
        attachMarkup(body, markup);
        return call("sendMessage", body, Duration.ofSeconds(30), Message.class);
    }

    public void sendChatAction(long chatId, String action) {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("action", action);
        call("sendChatAction", body, Duration.ofSeconds(10), Boolean.class);
    }

    public Message sendPlainMessage(long chatId, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", truncate(text));
        return call("sendMessage", body, Duration.ofSeconds(30), Message.class);
    }

    public void editMessageText(long chatId, int messageId, String text, InlineKeyboardMarkup markup) {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", truncate(text));
        body.put("parse_mode", "HTML");
        attachMarkup(body, markup);
        call("editMessageText", body, Duration.ofSeconds(30), Object.class);
    }

    public void editMessageReplyMarkup(long chatId, int messageId, InlineKeyboardMarkup markup) {
        ObjectNode body = mapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        attachMarkup(body, markup);
        call("editMessageReplyMarkup", body, Duration.ofSeconds(30), Object.class);
    }

    public void answerCallbackQuery(String callbackQueryId, String text, boolean alert) {
        ObjectNode body = mapper.createObjectNode();
        body.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {

            body.put("text", text.length() > 200 ? text.substring(0, 200) : text);
        }
        if (alert) {
            body.put("show_alert", true);
        }
        call("answerCallbackQuery", body, Duration.ofSeconds(15), Object.class);
    }

    private void attachMarkup(ObjectNode body, InlineKeyboardMarkup markup) {
        if (markup != null) {
            body.set("reply_markup", mapper.valueToTree(markup));
        }
    }

    /** Bot API limit: a bot can download files no larger than 20 MB. */
    public static final long MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;

    public Dto.File getFile(String fileId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("file_id", fileId);
        return call("getFile", body, Duration.ofSeconds(30), Dto.File.class);
    }

    /**
     * Downloads what getFile pointed at. Unlike every other call this one does not go through
     * the API root and its answer is the file itself, not a JSON envelope.
     */
    public byte[] downloadFile(String filePath) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileRoot + filePath))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new TelegramApiException("Network unavailable while downloading " + filePath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException("Download of " + filePath + " interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new TelegramApiException("Download of " + filePath + " -> HTTP " + response.statusCode(),
                    null, response.statusCode());
        }
        return response.body();
    }

    public Message sendVoice(long chatId, java.nio.file.Path ogg) {
        byte[] audio;
        try {
            audio = java.nio.file.Files.readAllBytes(ogg);
        } catch (IOException e) {
            throw new TelegramApiException("Cannot read voice file " + ogg, e);
        }
        String boundary = "----bebebe" + Long.toHexString(System.nanoTime());
        byte[] body = multipart(boundary, Map.of("chat_id", Long.toString(chatId)),
                "voice", "voice.ogg", "audio/ogg", audio);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiRoot + "sendVoice"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send("sendVoice", request, mapper.getTypeFactory().constructType(Message.class));
    }

    public Message sendDocument(long chatId, java.nio.file.Path file, String caption) {
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TelegramApiException("Cannot read file " + file, e);
        }
        String boundary = "----bebebe" + Long.toHexString(System.nanoTime());
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("chat_id", Long.toString(chatId));
        if (caption != null && !caption.isBlank()) {
            fields.put("caption", caption.length() > 1024 ? caption.substring(0, 1021) + "…" : caption);
        }
        byte[] body = multipart(boundary, fields, "document", file.getFileName().toString(),
                "application/octet-stream", bytes);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiRoot + "sendDocument"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send("sendDocument", request, mapper.getTypeFactory().constructType(Message.class));
    }

    static byte[] multipart(String boundary, Map<String, String> fields,
                            String fileField, String fileName, String contentType, byte[] file) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                        + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(file);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private <T> T call(String method, ObjectNode body, Duration timeout, Class<T> type) {
        return call(method, body, timeout, mapper.getTypeFactory().constructType(type));
    }

    private <T> T call(String method, ObjectNode body, Duration timeout, JavaType resultType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiRoot + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(method, request, resultType);
    }

    private <T> T send(String method, HttpRequest request, JavaType resultType) {

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TelegramApiException("Network unavailable when calling " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramApiException("Call " + method + " interrupted", e);
        }

        Envelope envelope;
        try {
            envelope = mapper.readValue(response.body(), Envelope.class);
        } catch (IOException e) {
            throw new TelegramApiException(
                    method + ": failed to parse the response (HTTP " + response.statusCode() + ")", e);
        }

        if (!envelope.ok()) {
            throw new TelegramApiException(
                    method + " -> " + envelope.errorCode() + " " + envelope.description(),
                    null,
                    envelope.errorCode());
        }

        try {

            return envelope.result() == null ? null : mapper.convertValue(envelope.result(), resultType);
        } catch (IllegalArgumentException e) {
            throw new TelegramApiException(method + ": unexpected shape of the result field", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope(boolean ok, Object result, int errorCode, String description) {
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_MESSAGE_LENGTH
                ? text
                : text.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    public static String escapeHtml(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @Override
    public void close() {
        http.close();
        log.debug("Telegram HTTP client closed");
    }
}
