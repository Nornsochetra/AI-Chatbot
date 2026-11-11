package com.kbprasac.chatbot.chat.service;

import com.kbprasac.chatbot.shared.enums.Channel;

/** A Botpress conversation was created for a chat session (Botpress Desk mode starts listening to it). */
public record BotConversationOpenedEvent(String sessionId, Channel channel, BotConversation conversation) {
}
