package com.kbprasac.chatbot.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.kbprasac.chatbot.audit.service.AuditService;
import com.kbprasac.chatbot.config.ChatProperties;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;
import com.kbprasac.chatbot.shared.repository.HandoverMessageRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatHistoryRetentionTest {

    @Mock
    private ChatEventRepository chatEvents;
    @Mock
    private HandoverMessageRepository handoverMessages;
    @Mock
    private AuditService audit;

    private ChatHistoryRetention retention(int days) {
        return new ChatHistoryRetention(chatEvents, handoverMessages, audit,
                new ChatProperties(Duration.ofMinutes(30), 2000, true, days));
    }

    @Test
    void deletesChatHistoryOlderThanTheRetentionAndAuditsIt() {
        when(chatEvents.deleteCreatedBefore(any())).thenReturn(3);
        when(handoverMessages.deleteCreatedBefore(any())).thenReturn(4);

        assertEquals(7, retention(90).deleteExpired());

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(chatEvents).deleteCreatedBefore(cutoff.capture());
        Duration age = Duration.between(cutoff.getValue(), Instant.now());
        assertEquals(90, age.toDays());
        verify(audit).record(eq(AuditService.SYSTEM), eq(ChatHistoryRetention.AUDIT_EXPIRED), anyString(), any(),
                anyString());
    }

    @Test
    void zeroKeepsEverything() {
        assertEquals(0, retention(0).deleteExpired());
        verify(chatEvents, never()).deleteCreatedBefore(any());
        verify(handoverMessages, never()).deleteCreatedBefore(any());
    }
}
