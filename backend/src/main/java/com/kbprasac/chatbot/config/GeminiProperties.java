package com.kbprasac.chatbot.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini API (Interactions endpoint) for voice: transcription and read-aloud.
 *
 * @param apiKey           from .env (GEMINI_API_KEY); empty disables voice (endpoints answer 503)
 * @param apiRevision      sent as the Api-Revision header
 * @param transcribeModels audio understanding models, tried in order: when one is overloaded (503 / 429)
 *                         or too slow, the next is used
 * @param ttsModels        text-to-speech models, tried in order the same way
 * @param voice            prebuilt TTS voice
 * @param timeout          per attempt (per model), so a hanging model leaves time for the next one
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(String apiKey, String baseUrl, String apiRevision, List<String> transcribeModels,
                               List<String> ttsModels, String voice, Duration timeout) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
