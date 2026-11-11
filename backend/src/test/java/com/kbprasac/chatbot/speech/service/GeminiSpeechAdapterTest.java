package com.kbprasac.chatbot.speech.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kbprasac.chatbot.config.GeminiProperties;
import com.kbprasac.chatbot.shared.exception.ServiceUnavailableException;

class GeminiSpeechAdapterTest {

    private static final String URL = "https://gemini.test/v1beta/interactions";

    private MockRestServiceServer server;

    private GeminiSpeechAdapter adapter(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test/v1beta");
        server = MockRestServiceServer.bindTo(builder).build();
        GeminiProperties props = new GeminiProperties(apiKey, "https://gemini.test/v1beta", "2026-05-20",
                List.of("gemini-3.8-flash", "gemini-3.5-flash"), List.of("gemini-3.8-flash-tts"), "Kore",
                Duration.ofSeconds(5));
        return new GeminiSpeechAdapter(builder.build(), props);
    }

    @Test
    void transcribeSendsInlineAudioAndReadsTheModelText() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        byte[] audio = "fake-webm".getBytes(StandardCharsets.UTF_8);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(header("Api-Revision", "2026-05-20"))
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"))
                .andExpect(jsonPath("$.input[0].type").value("text"))
                .andExpect(jsonPath("$.input[1].type").value("audio"))
                .andExpect(jsonPath("$.input[1].mime_type").value("audio/webm"))
                .andExpect(jsonPath("$.input[1].data").value(Base64.getEncoder().encodeToString(audio)))
                .andRespond(withSuccess("""
                        {"steps":[{"type":"user_input"},
                          {"type":"model_output","content":[{"type":"text","text":" តើសាខាបើកម៉ោងប៉ុន្មាន? "}]}]}""",
                        MediaType.APPLICATION_JSON));

        assertEquals("តើសាខាបើកម៉ោងប៉ុន្មាន?", adapter.transcribe(audio, "audio/webm"));
        server.verify();
    }

    @Test
    void transcribeAcceptsTheOutputTextShortcut() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"output_text\":\"hello\"}", MediaType.APPLICATION_JSON));
        assertEquals("hello", adapter.transcribe(new byte[] {1}, "audio/webm"));
    }

    @Test
    void synthesizeSendsTheTtsRequestAndDecodesTheWav() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        byte[] wav = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash-tts"))
                .andExpect(jsonPath("$.input[0].type").value("user_input"))
                .andExpect(jsonPath("$.input[0].content[0].text").value("សួស្តី"))
                .andExpect(jsonPath("$.response_format.type").value("audio"))
                .andExpect(jsonPath("$.generation_config.speech_config[0].voice").value("Kore"))
                .andRespond(withSuccess("{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\""
                        + Base64.getEncoder().encodeToString(wav) + "\"}]}]}", MediaType.APPLICATION_JSON));

        assertArrayEquals(wav, adapter.synthesize("សួស្តី"));
        server.verify();
    }

    @Test
    void readsTheResponseShapeSeenLive() {
        // trimmed from a real gemini-3.5-flash response on 2026-11-10
        GeminiSpeechAdapter adapter = adapter("test-key");
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"id":"i-1","status":"completed","usage":{},"created":"","updated":"","service_tier":"standard",
                 "steps":[{"type":"user_input"},{"type":"model_output","content":[{"type":"text",
                 "text":"សាខាបើកពីថ្ងៃចន្ទ ដល់ ថ្ងៃសុក្រ ម៉ោង 8 ព្រឹក"}]}],
                 "object":"interaction","model":"gemini-3.5-flash"}""", MediaType.APPLICATION_JSON));

        assertEquals("សាខាបើកពីថ្ងៃចន្ទ ដល់ ថ្ងៃសុក្រ ម៉ោង 8 ព្រឹក", adapter.transcribe(new byte[] {1}, "audio/wav"));
    }

    @Test
    void synthesizeWithoutAudioIsUnavailable() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"steps\":[]}", MediaType.APPLICATION_JSON));
        assertThrows(ServiceUnavailableException.class, () -> adapter.synthesize("hi"));
    }

    @Test
    void overloadedModelFallsThroughToTheNext() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"gemini-3.8-flash is currently experiencing high demand\"}}"));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("gemini-3.5-flash"))
                .andRespond(withSuccess("{\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("ok", adapter.transcribe(new byte[] {1}, "audio/webm"));
        server.verify();
    }

    @Test
    void allModelsFailingIsUnavailable() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThrows(ServiceUnavailableException.class, () -> adapter.transcribe(new byte[] {1}, "audio/webm"));
        server.verify();
    }

    @Test
    void rejectedRequestDoesNotTryOtherModels() {
        GeminiSpeechAdapter adapter = adapter("test-key");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)); // e.g. invalid key
        assertThrows(ServiceUnavailableException.class, () -> adapter.transcribe(new byte[] {1}, "audio/webm"));
        server.verify(); // exactly one request
    }

    @Test
    void noApiKeyMeansVoiceOffWithoutCallingGemini() {
        GeminiSpeechAdapter adapter = adapter("");
        assertThrows(ServiceUnavailableException.class, () -> adapter.transcribe(new byte[] {1}, "audio/webm"));
        assertThrows(ServiceUnavailableException.class, () -> adapter.synthesize("hi"));
        server.verify(); // no request was expected, none was made
    }
}
