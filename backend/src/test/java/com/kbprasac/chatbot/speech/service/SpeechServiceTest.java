package com.kbprasac.chatbot.speech.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;

@ExtendWith(MockitoExtension.class)
class SpeechServiceTest {

    @Mock
    private SpeechPort speech;
    @Mock
    private ChatEventRepository chatEventRepository;
    @InjectMocks
    private SpeechService service;

    @Test
    void transcribeAcceptsBrowserRecordingFormats() {
        when(speech.transcribe(any(), eq("audio/webm"))).thenReturn("hello");
        assertEquals("hello", service.transcribe(new byte[] {1}, "audio/webm;codecs=opus"));
    }

    @Test
    void transcribeRejectsEmptyAndUnsupportedAudio() {
        assertThrows(BadRequestException.class, () -> service.transcribe(new byte[0], "audio/webm"));
        assertThrows(BadRequestException.class, () -> service.transcribe(new byte[] {1}, "video/mp4"));
        assertThrows(BadRequestException.class, () -> service.transcribe(new byte[] {1}, null));
        verify(speech, never()).transcribe(any(), anyString());
    }

    @Test
    void readAloudSpeaksThePlainTextOfTheSessionsReply() {
        UUID id = UUID.randomUUID();
        when(chatEventRepository.findByIdAndSessionId(id, "s1"))
                .thenReturn(Optional.of(ChatEvent.builder().botReply("**Hours:** Mon–Fri\n- 8:00 to 15:00").build()));
        when(speech.synthesize(anyString())).thenReturn(new byte[] {9});

        service.readAloud("s1", id);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(speech).synthesize(text.capture());
        assertEquals("Hours: Mon–Fri\n8:00 to 15:00", text.getValue());
    }

    @Test
    void readAloudOnlyForTheSessionsOwnReplies() {
        UUID id = UUID.randomUUID();
        when(chatEventRepository.findByIdAndSessionId(id, "other-session")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.readAloud("other-session", id));
        verify(speech, never()).synthesize(anyString());
    }

    @Test
    void readAloudIsBoundedInLength() {
        UUID id = UUID.randomUUID();
        when(chatEventRepository.findByIdAndSessionId(id, "s1"))
                .thenReturn(Optional.of(ChatEvent.builder().botReply("a".repeat(5000)).build()));

        service.readAloud("s1", id);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(speech).synthesize(text.capture());
        assertEquals(SpeechService.MAX_SPEECH_CHARS, text.getValue().length());
    }

    @Test
    void plainTextRemovesMarkdown() {
        assertEquals("See the branch page.", SpeechService.plainText("See the [branch page](https://x.y)."));
        assertEquals("Fee is 10 dollars", SpeechService.plainText("Fee is **10 dollars**"));
        assertEquals("Opening documents\nID card\nPhoto",
                SpeechService.plainText("## Opening documents\n* ID card\n- Photo"));
        assertEquals("ប្រាក់ដាក់ដំបូងគឺ ១០ ដុល្លារ", SpeechService.plainText("ប្រាក់ដាក់ដំបូងគឺ **១០ ដុល្លារ**"));
        assertEquals("use code A1 now", SpeechService.plainText("use `code A1` now"));
    }

    @Test
    void mimeTypeIsNormalized() {
        assertEquals("audio/webm", SpeechService.normalizeMimeType("Audio/WebM; codecs=opus"));
        assertEquals("", SpeechService.normalizeMimeType(null));
    }
}
