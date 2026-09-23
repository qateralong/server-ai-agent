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
            Voice voice,
            Document document,

            /** Telegram sends the same photo in several sizes, smallest first. */
            List<PhotoSize> photo,
            String caption
    ) {
        public boolean hasText() {
            return text != null && !text.isBlank();
        }

        public boolean isVoice() {
            return voice != null;
        }

        public boolean hasDocument() {
            return document != null;
        }

        public boolean hasPhoto() {
            return photo != null && !photo.isEmpty();
        }

        /** The largest size is the one worth looking at; the others are thumbnails. */
        public java.util.Optional<PhotoSize> largestPhoto() {
            return photo == null ? java.util.Optional.empty()
                    : photo.stream().max(java.util.Comparator.comparingLong(
                            p -> (long) p.width() * p.height()));
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
    public record PhotoSize(String fileId, int width, int height, Long fileSize) {
    }

    /** An attached file. A .txt here can stand in for a long text answer -- see PendingInputs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(String fileId, String fileName, String mimeType, Long fileSize) {

        /** Plain text by extension or by MIME: those we are willing to read as an answer. */
        public boolean isPlainText() {
            String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
            String mime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(".txt") || name.endsWith(".md") || mime.startsWith("text/");
        }
    }

    /** Answer of getFile: {@code filePath} is relative to the file root, not to the API root. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record File(String fileId, String filePath, Long fileSize) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InlineKeyboardButton(String text, String callbackData) {

        /**
         * Every button label in the menu is built here, so this is the one place that has to
         * translate one. A label that is user data (a persona name, a note title) simply is
         * not in the catalogue and comes back unchanged.
         */
        public static InlineKeyboardButton of(String text, String callbackData) {
            return new InlineKeyboardButton(com.bebebe.agent.i18n.Messages.t(text), callbackData);
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
