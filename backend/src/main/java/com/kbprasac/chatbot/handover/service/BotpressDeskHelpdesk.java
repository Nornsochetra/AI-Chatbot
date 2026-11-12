package com.kbprasac.chatbot.handover.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.chat.service.AutoRouteDetector;
import com.kbprasac.chatbot.chat.service.BotConversations;
import com.kbprasac.chatbot.chat.service.BotEnginePort;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

/**
 * Botpress Desk as the agent inbox ({@code app.handover.inbox=botpress-desk}). The Desk agent works in the
 * session's Botpress conversation, so customer-side events are posted there as customer messages.
 * <ul>
 *   <li>Everything posted is fully masked: Botpress runs AI on the conversation. When masking hid personal data in
 *       a live chat message (account, card, phone, ID numbers), a link to our login-protected message page follows,
 *       so the agent can see it (BRD: agents may collect personal data; the AI may not). Secrets (OTP, PIN, CVV,
 *       password) are hidden there too.</li>
 *   <li>Files never go to Botpress: only a text notice (type, size, link to our login-protected file page).</li>
 *   <li>Desk shows every chat as "Webchat"; the channel (Website or Mobile Banking App) is named in the first line
 *       the agent gets (BRD: the agent sees the channel source).</li>
 *   <li>While WAITING, messages and files are posted at once, tagged "[Waiting for an agent]" and "· URGENT" when
 *       the message looks urgent, so agents see what each customer needs before joining and can take the urgent
 *       one first (BRD IV.C: context "before or during handover"). The bot is still active then and may reply to
 *       them in Desk; the customer never sees those replies, and the Studio instruction stops them.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.handover.inbox", havingValue = "botpress-desk", matchIfMissing = true)
public class BotpressDeskHelpdesk implements HelpdeskPort {

    private static final Logger log = LoggerFactory.getLogger(BotpressDeskHelpdesk.class);

    private final BotConversations conversations;
    private final BotEnginePort botEngine;
    private final InputMaskingService masking;
    private final AutoRouteDetector urgency;
    private final String publicBaseUrl;
    /** sessions our side closed while a Desk agent still had the ticket; the bot stays silent until Desk closes it */
    private final Set<String> awaitingDeskClose = ConcurrentHashMap.newKeySet();
    /** sessions where a Desk agent joined without being connected; Desk pauses the bot until the ticket is closed */
    private final Set<String> pausedByDesk = ConcurrentHashMap.newKeySet();
    /** "[Not connected]" notes already posted (session|agent|reason), so each is posted once */
    private final Set<String> notesPosted = ConcurrentHashMap.newKeySet();

    /** Why an agent's join or reply was not passed to the customer (assignment rules, docs/handover.md). */
    public enum NotConnected {
        /** the customer has no open request (never asked, cancelled or ended) */
        NO_REQUEST,
        /** another agent already has this chat */
        OTHER_AGENT,
        /** the agent is at the limit of live chats */
        AGENT_AT_LIMIT
    }

    public BotpressDeskHelpdesk(BotConversations conversations, BotEnginePort botEngine, InputMaskingService masking,
                                AutoRouteDetector urgency, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.conversations = conversations;
        this.botEngine = botEngine;
        this.masking = masking;
        this.urgency = urgency;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void ticketOpened(Handover handover, List<ChatEvent> botTranscript) {
        String why = switch (handover.getReason()) {
            case BOT_SUGGESTED -> "the bot could not answer";
            case AUTO_URGENT -> "routed automatically: the customer's message looks urgent, e.g. fraud, a lost or "
                    + "stolen card, or a hacked account";
            case AUTO_ASKED_FOR_AGENT -> "routed automatically: the customer asked for a person in their message";
            case AUTO_BOT_FAILED -> "routed automatically: the bot could not answer twice in a row";
            default -> "the customer pressed \"Talk to an agent\"";
        };
        String tag = handover.getReason() == HandoverReason.AUTO_URGENT ? "[Live agent requested · URGENT]"
                : "[Live agent requested]";
        post(handover, tag + " The customer asked to talk to a live agent from "
                + channelName(handover.getChannel()) + " (" + why
                + "). Join this conversation to help; until then the customer sees a waiting message. Messages they "
                + "write while waiting appear here as \"[Waiting for an agent]\".");
    }

    @Override
    public void filesSentBefore(Handover handover, List<Attachment> files) {
        if (!files.isEmpty()) {
            post(handover, "[Files the customer sent to the assistant before asking for an agent]\n"
                    + files.stream().map(f -> "- " + fileNotice(f)).collect(Collectors.joining("\n")));
        }
    }

    @Override
    public void customerMessage(Handover handover, HandoverMessage message) {
        if (handover.getStatus() == HandoverStatus.ACTIVE) {
            post(handover, forDesk(handover, message));
        } else if (handover.getStatus() == HandoverStatus.WAITING) {
            post(handover, waitingTag(message.getText()) + " " + forDesk(handover, message));
        }
    }

    /** So agents see before joining what each waiting customer needs, and which one is urgent. */
    private String waitingTag(String text) {
        boolean urgent = urgency.detect(text).filter(r -> r == HandoverReason.AUTO_URGENT).isPresent();
        return urgent ? "[Waiting for an agent · URGENT]" : "[Waiting for an agent]";
    }

    /** Fully masked for Botpress; if that hid personal data, a secure link to the message as the agent may see it. */
    String forDesk(Handover handover, HandoverMessage message) {
        InputMaskingService.MaskResult masked = masking.mask(message.getText());
        if (!masked.changed() || message.getId() == null) {
            return masked.text();
        }
        // two spaces: a line break in Desk's Markdown
        return masked.text() + "  \n🔒 Personal details hidden from the AI. Open securely (agent sign-in required): "
                + publicBaseUrl + "/agent/message.html?m=" + handover.getId() + "." + message.getId();
    }

    @Override
    public void customerAttachment(Handover handover, Attachment attachment) {
        if (handover.getStatus() == HandoverStatus.ACTIVE) {
            post(handover, fileNotice(attachment));
        } else if (handover.getStatus() == HandoverStatus.WAITING) {
            post(handover, "[Waiting for an agent] " + fileNotice(attachment));
        }
    }

    @Override
    public void ticketClosed(Handover handover) {
        // a Desk close is already known to Desk; tell the agent only when our side ended the chat
        HandoverCloseReason reason = handover.getCloseReason();
        if (reason == HandoverCloseReason.AGENT_CLOSED) {
            return;
        }
        boolean agentHadIt = handover.getAcceptedAt() != null;
        if (agentHadIt) {
            // Desk keeps the bot silent until the agent closes the ticket
            awaitingDeskClose.add(handover.getSessionId());
        }
        String why = switch (reason) {
            case CUSTOMER_INACTIVE -> "[Chat ended] The customer was inactive, so the live chat was closed on the customer side.";
            case CUSTOMER_ENDED -> agentHadIt
                    ? "[Customer ended the chat] The customer will not see further messages."
                    : "[Customer cancelled the request] No need to join this chat.";
            case AGENT_CLOSED -> "";
        };
        post(handover, agentHadIt ? why + " Please close this ticket so the assistant can answer again." : why);
    }

    @Override
    public boolean stillOpenAfterOurClose(String sessionId) {
        return awaitingDeskClose.contains(sessionId);
    }

    @Override
    public boolean botPausedByInbox(String sessionId) {
        return pausedByDesk.contains(sessionId);
    }

    /** The agent closed the ticket in Desk: the bot answers again. */
    public void deskTicketClosed(String sessionId) {
        awaitingDeskClose.remove(sessionId);
        pausedByDesk.remove(sessionId);
        notesPosted.removeIf(key -> key.startsWith(sessionId + "|"));
    }

    /**
     * An agent's join or reply was not passed to the customer: tell that agent why, once per chat and reason.
     * Desk still lets them sit in the conversation; with no request open, Desk also pauses the bot.
     *
     * @param owner  OTHER_AGENT: the agent who has the chat
     * @param limit  AGENT_AT_LIMIT: the live chats allowed per agent
     */
    public void notConnected(String sessionId, Channel channel, String agentKey, NotConnected reason, String owner,
                             int limit) {
        if (reason == NotConnected.NO_REQUEST) {
            pausedByDesk.add(sessionId);
        }
        if (!notesPosted.add(sessionId + "|" + agentKey + "|" + reason)) {
            return;
        }
        String note = switch (reason) {
            case NO_REQUEST -> "[Not connected] This customer has not asked for a live agent, so your messages are "
                    + "not shown to them. The assistant is paused while you are in this chat: please close the ticket. "
                    + "If the customer asks for an agent, you will see \"[Live agent requested]\" here.";
            case OTHER_AGENT -> "[Not connected] " + (owner == null ? "Another agent" : owner)
                    + " is already helping this customer. Your messages are not shown to them.";
            case AGENT_AT_LIMIT -> "[Not connected] You already have " + limit + " live chat" + (limit == 1 ? "" : "s")
                    + ", the most allowed at once. Finish one first; this customer stays in the queue and you can "
                    + "reply here once you are free.";
        };
        postTo(sessionId, channel, note);
    }

    /**
     * A Desk agent joined a bot chat the customer had not escalated ({@code AGENT_TAKEOVER=true}): the agent never got
     * the "[Live agent requested]" line, so the channel is named here.
     */
    public void agentTookOver(Handover handover) {
        post(handover, "[You joined this chat] The customer is chatting from " + channelName(handover.getChannel())
                + ".");
    }

    static String channelName(Channel channel) {
        return channel == Channel.MOBILE_APP ? "the Mobile Banking App" : "the Website";
    }


    /** No file name (it can contain personal data) and no file: type, size and a link that needs the agent login. */
    String fileNotice(Attachment attachment) {
        String kind = attachment.getContentType().startsWith("image/") ? "image" : "PDF";
        return "📎 Customer sent a file (" + kind + ", " + size(attachment.getSizeBytes()) + "). "
                // one parameter without "&": Desk shows "&" in links as "&amp;", which breaks a second parameter
                + "Open it securely (agent sign-in required): " + publicBaseUrl + "/agent/file.html?f="
                + attachment.getHandoverId() + "." + attachment.getId();
    }

    private void post(Handover handover, String text) {
        postTo(handover.getSessionId(), handover.getChannel(), text);
    }

    private void postTo(String sessionId, Channel channel, String text) {
        try {
            botEngine.send(conversations.ensure(sessionId, channel), text);
        } catch (RuntimeException e) {
            // the handover itself stays valid; the Desk agent just misses this line
            log.warn("Could not post to Botpress for session {}", sessionId, e);
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
