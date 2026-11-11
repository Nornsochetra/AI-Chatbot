package com.kbprasac.chatbot.chat.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.shared.enums.Channel;

/** Opens the Botpress conversation of a chat session on first use. */
@Component
public class BotConversations {

    private final BotEnginePort botEngine;
    private final ChatSessionStore sessionStore;
    private final ApplicationEventPublisher events;

    public BotConversations(BotEnginePort botEngine, ChatSessionStore sessionStore, ApplicationEventPublisher events) {
        this.botEngine = botEngine;
        this.sessionStore = sessionStore;
        this.events = events;
    }

    public BotConversation ensure(ChatSession session) {
        if (session.conversation() != null) {
            return session.conversation();
        }
        BotConversation conversation = botEngine.openConversation();
        sessionStore.save(session.withConversation(conversation));
        events.publishEvent(new BotConversationOpenedEvent(session.sessionId(), session.channel(), conversation));
        return conversation;
    }

    public BotConversation ensure(String sessionId, Channel channel) {
        return ensure(sessionStore.resolve(sessionId, channel));
    }
}
