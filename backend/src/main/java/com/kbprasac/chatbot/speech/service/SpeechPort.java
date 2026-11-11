package com.kbprasac.chatbot.speech.service;

/** Swap point for voice. The default adapter calls the Gemini API. */
public interface SpeechPort {

    /** Speech-to-text. Returns the transcript, empty when there was no speech. */
    String transcribe(byte[] audio, String mimeType);

    /** Text-to-speech. Returns a playable WAV file. */
    byte[] synthesize(String text);
}
