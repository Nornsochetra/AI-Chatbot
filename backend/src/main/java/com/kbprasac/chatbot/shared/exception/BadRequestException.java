package com.kbprasac.chatbot.shared.exception;

/** Invalid input that bean validation cannot express (e.g. an unsupported audio format). Maps to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
