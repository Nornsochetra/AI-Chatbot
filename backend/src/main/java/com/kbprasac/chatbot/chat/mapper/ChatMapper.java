package com.kbprasac.chatbot.chat.mapper;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.chat.payload.response.ChatMessageResponse;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;

@Component
public class ChatMapper {

    public ChatMessageResponse toResponse(String sessionId, String reply, UUID replyId, SessionState state,
                                          boolean handoverSuggested, boolean inputMasked) {
        return new ChatMessageResponse(sessionId, reply, replyId, state, handoverSuggested, inputMasked, Instant.now(),
                null);
    }

    /** The message opened a live agent request (auto-route): the widget switches to waiting. */
    public ChatMessageResponse toRoutedResponse(String sessionId, boolean inputMasked) {
        return new ChatMessageResponse(sessionId, null, null, SessionState.HUMAN_ACTIVE, false, inputMasked,
                Instant.now(), HandoverStatus.WAITING);
    }

    public ChatEvent toEvent(String sessionId, Channel channel, String maskedUserText, String botReply,
                             boolean inputMasked, boolean handoverSuggested) {
        return ChatEvent.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .channel(channel)
                .userText(maskedUserText)
                .botReply(botReply)
                .inputMasked(inputMasked)
                .handoverSuggested(handoverSuggested)
                .createdAt(Instant.now())
                .build();
    }
}
