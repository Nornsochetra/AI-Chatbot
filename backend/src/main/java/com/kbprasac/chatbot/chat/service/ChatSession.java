package com.kbprasac.chatbot.chat.service;

import com.kbprasac.chatbot.shared.enums.Channel;

/**
 * A customer chat session. {@code conversation} is null until the first message reaches Botpress.
 * Whether a live agent is active is not stored here: it comes from the open handover in the database.
 */
public record ChatSession(String sessionId, Channel channel, BotConversation conversation) {

    public ChatSession withConversation(BotConversation newConversation) {
        return new ChatSession(sessionId, channel, newConversation);
    }
}
