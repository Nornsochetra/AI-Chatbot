package com.kbprasac.chatbot.shared.exception;

/** 429: a per-chat limit was reached (e.g. files sent to the AI chat). */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
