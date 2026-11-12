package com.kbprasac.chatbot.handover.service;

import java.util.List;

import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;

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

    /** A customer message in a live chat, as stored (secrets masked; personal data such as account numbers kept). */
    void customerMessage(Handover handover, HandoverMessage message);

    /** Files the customer sent to the assistant before the handover, now given to the agent (links, not files). */
    default void filesSentBefore(Handover handover, List<Attachment> files) {
    }

    /** The customer uploaded a file (the file itself stays with us). */
    default void customerAttachment(Handover handover, Attachment attachment) {
    }

    void ticketClosed(Handover handover);

    /**
     * Our side closed the chat (customer ended it, or inactivity) but the inbox still holds it open, so the bot
     * may stay silent for a while. Only Botpress Desk has this; our console does not.
     */
    default boolean stillOpenAfterOurClose(String sessionId) {
        return false;
    }

    /**
     * An agent is in the chat in the inbox but was not connected (the customer did not ask), and the inbox pauses the
     * bot meanwhile. Only Botpress Desk has this.
     */
    default boolean botPausedByInbox(String sessionId) {
        return false;
    }
}
