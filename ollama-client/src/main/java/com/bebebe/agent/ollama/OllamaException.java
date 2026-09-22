package com.bebebe.agent.ollama;

public class OllamaException extends com.bebebe.agent.llm.LlmException {

    public OllamaException(String message) {
        this(message, null, 0);
    }

    public OllamaException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    public OllamaException(String message, Throwable cause, int httpStatus) {
        super(message, cause, httpStatus);
    }

}
