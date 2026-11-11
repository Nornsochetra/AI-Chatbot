package com.kbprasac.chatbot.shared.exception;

/** An external service (e.g. Gemini voice) is not configured or not reachable. Maps to HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
