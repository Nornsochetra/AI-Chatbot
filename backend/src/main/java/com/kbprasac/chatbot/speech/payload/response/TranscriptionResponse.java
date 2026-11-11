package com.kbprasac.chatbot.speech.payload.response;

/** The transcript for the customer's input box; empty when no speech was heard. */
public record TranscriptionResponse(String text) {
}
