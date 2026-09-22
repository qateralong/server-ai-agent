package com.bebebe.agent.llm;

public class LlmException extends RuntimeException {

    private final int httpStatus;

    public LlmException(String message) {
        this(message, null, 0);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    public LlmException(String message, Throwable cause, int httpStatus) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
