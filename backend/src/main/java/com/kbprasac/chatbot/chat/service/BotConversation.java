package com.kbprasac.chatbot.chat.service;

/** Botpress identifiers for one customer conversation. */
public record BotConversation(String userId, String userKey, String conversationId) {
}
