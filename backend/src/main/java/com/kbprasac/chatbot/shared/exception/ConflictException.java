package com.kbprasac.chatbot.shared.exception;

/** The request is valid but not allowed in the current state (e.g. replying to a closed handover). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
