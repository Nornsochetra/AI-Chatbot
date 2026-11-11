package com.kbprasac.chatbot.speech.controller;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.speech.payload.response.TranscriptionResponse;
import com.kbprasac.chatbot.speech.service.SpeechService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Customer side (widget). Public like the chat endpoint. 503 when voice is not configured or Gemini is down. */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Voice")
public class SpeechController {

    private static final MediaType AUDIO_WAV = MediaType.parseMediaType("audio/wav");

    private final SpeechService speechService;

    public SpeechController(SpeechService speechService) {
        this.speechService = speechService;
    }

    @PostMapping(path = "/speech/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Speech-to-text: multipart field 'audio' (WebM / Ogg / MP4 / WAV …, max 5 MB); returns the transcript")
    public TranscriptionResponse transcribe(@RequestPart("audio") MultipartFile audio) {
        try {
            return new TranscriptionResponse(speechService.transcribe(audio.getBytes(), audio.getContentType()));
        } catch (IOException e) {
            throw new BadRequestException("Could not read the recording");
        }
    }

    @PostMapping(path = "/sessions/{sessionId}/replies/{replyId}/speech", produces = "audio/wav")
    @Operation(summary = "Read-aloud: a bot reply of this session as WAV audio")
    public ResponseEntity<byte[]> readAloud(@PathVariable String sessionId, @PathVariable UUID replyId) {
        return ResponseEntity.ok()
                .contentType(AUDIO_WAV)
                .cacheControl(CacheControl.noStore())
                .body(speechService.readAloud(sessionId, replyId));
    }
}
