package com.kbprasac.chatbot.handover.service;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.chat.service.BotConversations;
import com.kbprasac.chatbot.chat.service.BotEnginePort;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.MessageSender;

/**
 * Botpress Desk as the agent inbox ({@code app.handover.inbox=botpress-desk}). The Desk agent works in the
 * session's Botpress conversation, so customer-side events are posted there as customer messages.
 * <ul>
 *   <li>Everything posted is fully masked: Botpress runs AI on the conversation.</li>
 *   <li>Files never go to Botpress: only a text notice (type, size, link to our login-protected file page).</li>
 *   <li>While WAITING the bot is still active and would answer each message, so messages are held back and
 *       sent in one block when the agent joins ({@link #forwardWaitingMessages}).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.handover.inbox", havingValue = "botpress-desk")
public class BotpressDeskHelpdesk implements HelpdeskPort {

    private static final Logger log = LoggerFactory.getLogger(BotpressDeskHelpdesk.class);

    private final BotConversations conversations;
    private final BotEnginePort botEngine;
    private final InputMaskingService masking;
    private final String publicBaseUrl;

    public BotpressDeskHelpdesk(BotConversations conversations, BotEnginePort botEngine, InputMaskingService masking,
                                @Value("${app.public-base-url}") String publicBaseUrl) {
        this.conversations = conversations;
        this.botEngine = botEngine;
        this.masking = masking;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void ticketOpened(Handover handover, List<ChatEvent> botTranscript) {
        String why = handover.getReason() == HandoverReason.BOT_SUGGESTED
                ? "the bot could not answer" : "the customer pressed \"Talk to an agent\"";
        post(handover, "[Live agent requested] The customer asked to talk to a live agent (" + why
                + "). Join this conversation to help; until then the customer sees a waiting message.");
    }

    @Override
    public void customerMessage(Handover handover, String maskedText) {
        if (handover.getStatus() == HandoverStatus.ACTIVE) {
            post(handover, masking.mask(maskedText).text());
        }
    }

    @Override
    public void customerAttachment(Handover handover, Attachment attachment) {
        if (handover.getStatus() == HandoverStatus.ACTIVE) {
            post(handover, fileNotice(attachment));
        }
    }

    @Override
    public void ticketClosed(Handover handover) {
        // a Desk close is already known to Desk; tell the agent only when our side ended the chat
        if (handover.getCloseReason() != HandoverCloseReason.AGENT_CLOSED) {
            post(handover, "[Chat ended] The customer was inactive, so the live chat was closed on the customer side. "
                    + "Please close this ticket.");
        }
    }

    /** The agent joined: send what the customer wrote while waiting, in one message. */
    public void forwardWaitingMessages(Handover handover, List<HandoverMessage> messages) {
        String text = messages.stream()
                .filter(m -> m.getSender() == MessageSender.CUSTOMER)
                .map(m -> m.getAttachment() != null ? fileNotice(m.getAttachment()) : masking.mask(m.getText()).text())
                .map(line -> "- " + line)
                .collect(Collectors.joining("\n"));
        if (!text.isEmpty()) {
            post(handover, "[While waiting for an agent, the customer wrote]\n" + text);
        }
    }

    /** No file name (it can contain personal data) and no file: type, size and a link that needs the agent login. */
    String fileNotice(Attachment attachment) {
        String kind = attachment.getContentType().startsWith("image/") ? "image" : "PDF";
        return "📎 Customer sent a file (" + kind + ", " + size(attachment.getSizeBytes()) + "). "
                + "Open it securely (agent sign-in required): " + publicBaseUrl + "/agent/file.html?handover="
                + attachment.getHandoverId() + "&file=" + attachment.getId();
    }

    private void post(Handover handover, String text) {
        try {
            botEngine.send(conversations.ensure(handover.getSessionId(), handover.getChannel()), text);
        } catch (RuntimeException e) {
            // the handover itself stays valid; the Desk agent just misses this line
            log.warn("Could not post to Botpress for handover {}", handover.getId(), e);
        }
    }

    private static String size(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
