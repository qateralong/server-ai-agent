package com.bebebe.agent.llm;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * An image attached to the current question, as raw bytes plus its media type.
 *
 * <p>Base64 rather than a URL or an uploaded file id: the picture comes from Telegram, is looked
 * at once and is not worth storing anywhere. Both providers take base64, and it is the only form
 * both of them take.
 */
public record LlmImage(byte[] bytes, String mediaType) {

    /** What both providers accept. Anything else is refused before it gets this far. */
    public static final List<String> SUPPORTED = List.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /** Claude's own limit on a base64 image; Ollama has no documented one, so this stands for both. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public LlmImage {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("An empty image");
        }
        mediaType = mediaType == null || mediaType.isBlank()
                ? "image/jpeg"
                : mediaType.strip().toLowerCase(Locale.ROOT);
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static boolean isSupported(String mediaType) {
        return mediaType != null && SUPPORTED.contains(mediaType.strip().toLowerCase(Locale.ROOT));
    }

    /** Guessed from the file name when Telegram does not say; it usually does not for photos. */
    public static String mediaTypeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    @Override
    public String toString() {
        return "LlmImage[" + mediaType + ", " + bytes.length + " bytes]";
    }
}
