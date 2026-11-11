package com.kbprasac.chatbot.handover.service;

import java.util.List;

import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;

/**
 * Swap point for the agent inbox. {@link HandoverService} owns the handover state and messages;
 * the port tells the inbox what happened on the customer side.
 * <p>
 * Picked by {@code app.handover.inbox}:
 * <ul>
 *   <li>{@code console}: nothing to forward, the console reads our tables directly ({@link BuiltInHelpdesk})</li>
 *   <li>{@code botpress-desk}: posts to the Botpress conversation the Desk agent sees ({@link BotpressDeskHelpdesk});
 *       {@link BotpressDeskEvents} feeds Desk agent actions back</li>
 * </ul>
 * Another external inbox (Chatwoot, the bank's helpdesk): forward these calls to its API, and call
 * {@link HandoverService#accept}, {@link HandoverService#agentMessage} and {@link HandoverService#close}
 * from its webhooks.
 */
public interface HelpdeskPort {

    void ticketOpened(Handover handover, List<ChatEvent> botTranscript);

    void customerMessage(Handover handover, String maskedText);

    /** The customer uploaded a file (the file itself stays with us). */
    default void customerAttachment(Handover handover, Attachment attachment) {
    }

    void ticketClosed(Handover handover);
}
