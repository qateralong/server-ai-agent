package com.bebebe.agent.telegram.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public final class Dto {

    private Dto() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(long updateId, Message message, CallbackQuery callbackQuery) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            int messageId,
            User from,
            Chat chat,
            long date,
            String text,
            Voice voice
    ) {
        public boolean hasText() {
            return text != null && !text.isBlank();
        }

        public boolean isVoice() {
            return voice != null;
        }

        public String command() {
            if (!hasText() || !text.startsWith("/")) {
                return "";
            }
            String head = text.split("\\s+", 2)[0].substring(1);
            int at = head.indexOf('@');
            return (at >= 0 ? head.substring(0, at) : head).toLowerCase(java.util.Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, Boolean isBot, String firstName, String username) {

        public String describe() {
            return (username == null || username.isBlank() ? "" : "@" + username + " ") + "(" + id + ")";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id, String type, String username, String title) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Voice(String fileId, int duration, String mimeType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InlineKeyboardButton(String text, String callbackData) {

        public static InlineKeyboardButton of(String text, String callbackData) {
            return new InlineKeyboardButton(text, callbackData);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InlineKeyboardMarkup(List<List<InlineKeyboardButton>> inlineKeyboard) {

        public static InlineKeyboardMarkup of(List<List<InlineKeyboardButton>> rows) {
            return new InlineKeyboardMarkup(List.copyOf(rows));
        }

        public static InlineKeyboardMarkup row(InlineKeyboardButton... buttons) {
            return new InlineKeyboardMarkup(List.of(List.of(buttons)));
        }

        public static InlineKeyboardMarkup empty() {
            return new InlineKeyboardMarkup(List.of());
        }

        public int buttonCount() {
            return inlineKeyboard.stream().mapToInt(List::size).sum();
        }
    }
}
