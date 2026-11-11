package com.kbprasac.chatbot.speech.service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.kbprasac.chatbot.config.GeminiProperties;
import com.kbprasac.chatbot.shared.exception.ServiceUnavailableException;

/**
 * Gemini Interactions API ({@code POST /v1beta/interactions}).
 * Transcription: text + inline audio input, text output. Read-aloud: TTS model, WAV output
 * (24 kHz, mono, 16-bit PCM for unary requests).
 * Response: {@code steps[]} → the {@code model_output} step → {@code content[]} items of type text / audio
 * (confirmed with live calls on 2026-11-10).
 * <p>
 * Models are tried in the configured order: Gemini models are sometimes overloaded ("high demand", 503) or hang,
 * so an overloaded, rate-limited, missing or too slow model falls through to the next one.
 */
@Component
public class GeminiSpeechAdapter implements SpeechPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiSpeechAdapter.class);

    static final String TRANSCRIBE_PROMPT = """
            Transcribe this audio exactly as spoken. The speaker is a bank customer using Khmer or English, \
            possibly mixed. Write Khmer in Khmer script and English in Latin script. Write all numbers as digits. \
            Return only the transcript text, with no explanations, labels or translation. \
            If there is no speech, return nothing.""";

    /** Worth trying the next model: overloaded / rate limited / server error / model name not available. */
    private static final Set<Integer> TRY_NEXT_STATUS = Set.of(404, 429, 500, 502, 503, 504);

    private final RestClient client;
    private final GeminiProperties props;

    public GeminiSpeechAdapter(RestClient geminiRestClient, GeminiProperties props) {
        this.client = geminiRestClient;
        this.props = props;
    }

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        String data = Base64.getEncoder().encodeToString(audio);
        JsonNode response = callWithFallback(props.transcribeModels(), model -> Map.of(
                "model", model,
                "input", List.of(
                        Map.of("type", "text", "text", TRANSCRIBE_PROMPT),
                        Map.of("type", "audio", "data", data, "mime_type", mimeType))));
        return String.join("", outputs(response, "text", "text")).trim();
    }

    @Override
    public byte[] synthesize(String text) {
        JsonNode response = callWithFallback(props.ttsModels(), model -> Map.of(
                "model", model,
                "input", List.of(Map.of(
                        "type", "user_input",
                        "content", List.of(Map.of("type", "text", "text", text)))),
                "response_format", Map.of("type", "audio"),
                "generation_config", Map.of("speech_config", List.of(Map.of("voice", props.voice())))));
        List<String> audio = outputs(response, "audio", "data");
        if (audio.isEmpty()) {
            log.warn("Gemini TTS returned no audio; response fields: {}", fieldNames(response));
            throw new ServiceUnavailableException("Read-aloud returned no audio");
        }
        return Base64.getDecoder().decode(audio.get(audio.size() - 1));
    }

    private JsonNode callWithFallback(List<String> models, Function<String, Map<String, Object>> body) {
        if (!props.enabled()) {
            throw new ServiceUnavailableException("Voice is not configured (GEMINI_API_KEY is empty)");
        }
        if (models == null || models.isEmpty()) {
            throw new ServiceUnavailableException("No Gemini model configured");
        }
        List<String> failures = new ArrayList<>();
        for (String model : models) {
            try {
                JsonNode response = call(body.apply(model));
                if (!failures.isEmpty()) {
                    log.info("Gemini {} answered after: {}", model, failures);
                }
                return response;
            } catch (RestClientResponseException e) {
                if (!TRY_NEXT_STATUS.contains(e.getStatusCode().value())) {
                    // bad request / invalid key: another model will not help
                    throw new ServiceUnavailableException("Gemini " + model + " rejected the request: "
                            + e.getStatusCode().value() + " " + shortBody(e), e);
                }
                failures.add(model + " " + e.getStatusCode().value());
            } catch (ResourceAccessException e) {
                failures.add(model + " timeout/network");
            } catch (RestClientException e) {
                failures.add(model + " " + e.getClass().getSimpleName());
            }
        }
        // never log the request body: it contains the customer's audio or text
        throw new ServiceUnavailableException("All Gemini models failed: " + failures);
    }

    private JsonNode call(Map<String, Object> body) {
        JsonNode response = client.post().uri("/interactions")
                .header("x-goog-api-key", props.apiKey())
                .header("Api-Revision", props.apiRevision())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new RestClientException("Empty response from Gemini");
        }
        return response;
    }

    /** Collects {@code field} of every content item of {@code type} in the model output steps. */
    static List<String> outputs(JsonNode response, String type, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            for (JsonNode item : step.path("content")) {
                if (type.equals(item.path("type").asText()) && item.hasNonNull(field)) {
                    values.add(item.path(field).asText());
                }
            }
        }
        // the SDKs expose a convenience output_text; accept it if the REST response carries it
        if (values.isEmpty() && "text".equals(type) && response.hasNonNull("output_text")) {
            values.add(response.path("output_text").asText());
        }
        return values;
    }

    private static String shortBody(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
