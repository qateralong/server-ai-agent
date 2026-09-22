package com.bebebe.agent.telegram.api;

public class TelegramApiException extends RuntimeException {

    private final int errorCode;

    public TelegramApiException(String message) {
        this(message, null, 0);
    }

    public TelegramApiException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    public TelegramApiException(String message, Throwable cause, int errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }

    public boolean isNotModified() {
        String message = getMessage();
        return message != null && message.contains("message is not modified");
    }
}
