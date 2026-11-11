package com.kbprasac.chatbot.chat.service;

import java.util.Optional;

/** Swap point for the bot brain. The default adapter calls the Botpress Chat API. */
public interface BotEnginePort {

    BotConversation openConversation();

    /** Returns the bot reply, or empty when no reply arrived in time. */
    Optional<String> ask(BotConversation conversation, String text);

    /** Posts a message as the customer without waiting for a reply (Botpress Desk mode: text for the agent). */
    void send(BotConversation conversation, String text);
}
