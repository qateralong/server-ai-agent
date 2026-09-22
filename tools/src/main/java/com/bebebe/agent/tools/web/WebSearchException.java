package com.bebebe.agent.tools.web;

public class WebSearchException extends RuntimeException {

    public WebSearchException(String message) {
        super(message);
    }

    public WebSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
