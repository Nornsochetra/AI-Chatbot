package com.kbprasac.chatbot.handover.service;

import java.util.List;

import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;

/**
 * Swap point for the agent inbox. {@link HandoverService} owns the handover state and messages;
 * the port tells the inbox what happened on the customer side.
 * <p>
 * Built-in agent console: nothing to forward, the console reads our tables directly ({@link BuiltInHelpdesk}).
 * External inbox (Chatwoot, the bank's helpdesk): forward these calls to its API, and call
 * {@link HandoverService#accept}, {@link HandoverService#agentMessage} and {@link HandoverService#close}
 * from its webhooks.
 */
public interface HelpdeskPort {

    void ticketOpened(Handover handover, List<ChatEvent> botTranscript);

    void customerMessage(Handover handover, String maskedText);

    void ticketClosed(Handover handover);
}
