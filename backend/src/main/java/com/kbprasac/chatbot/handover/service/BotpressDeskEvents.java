package com.kbprasac.chatbot.handover.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.kbprasac.chatbot.chat.service.BotpressMessages;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

/**
 * Turns Botpress live-stream events into handover changes (Botpress Desk mode).
 * Only {@code message_created} matters (verified 2026-11-11):
 * <ul>
 *   <li>{@code metadata.hitl.kind = agent_assigned} (with {@code agentName}): an agent joined → accept, or open an
 *       ACTIVE handover when the customer had not asked</li>
 *   <li>{@code metadata.adminName}: an agent reply → shown in the widget as an agent message</li>
 *   <li>{@code metadata.hitl.kind = ticket_closed}: the agent closed the ticket → close (the bot answers again)</li>
 * </ul>
 * Ignored: bot answers (our chat flow reads those), {@code ticket_opened}, and Desk's own {@code csat_request}
 * (our rating card is used instead).
 */
@Component
@ConditionalOnProperty(name = "app.handover.inbox", havingValue = "botpress-desk")
public class BotpressDeskEvents {

    private static final Logger log = LoggerFactory.getLogger(BotpressDeskEvents.class);

    private final HandoverService handoverService;
    private final BotpressDeskHelpdesk helpdesk;

    public BotpressDeskEvents(HandoverService handoverService, BotpressDeskHelpdesk helpdesk) {
        this.handoverService = handoverService;
        this.helpdesk = helpdesk;
    }

    public void onEvent(String sessionId, Channel channel, JsonNode event) {
        if (!"message_created".equals(event.path("type").asText())) {
            return;
        }
        JsonNode message = event.path("data");
        String kind = BotpressMessages.hitlKind(message);
        if ("agent_assigned".equals(kind)) {
            agentJoined(sessionId, channel, firstName(message.path("metadata").path("hitl").path("agentName").asText(null)));
        } else if ("ticket_closed".equals(kind)) {
            handoverService.findOpen(sessionId)
                    .ifPresent(h -> handoverService.close(h.getId(), HandoverCloseReason.AGENT_CLOSED));
        } else if (BotpressMessages.isAgentMessage(message)) {
            agentReplied(sessionId, channel, firstName(message.path("metadata").path("adminName").asText(null)),
                    message.path("payload"));
        }
    }

    private Handover agentJoined(String sessionId, Channel channel, String agentName) {
        Optional<Handover> open = handoverService.findOpen(sessionId);
        if (open.isEmpty()) {
            log.info("Desk agent took over session {} without a customer request", sessionId);
            return handoverService.startByAgent(sessionId, channel, agentName);
        }
        Handover handover = open.get();
        if (handover.getStatus() != HandoverStatus.WAITING) {
            return handover;
        }
        Handover accepted = handoverService.accept(handover.getId(), agentName);
        helpdesk.forwardWaitingMessages(accepted, handoverService.messagesForHandover(accepted.getId(), 0));
        return accepted;
    }

    private void agentReplied(String sessionId, Channel channel, String agentName, JsonNode payload) {
        String text = payload.path("text").asText("");
        if (text.isBlank()) {
            log.warn("Desk agent message of type {} in session {} is not text; not shown to the customer",
                    payload.path("type").asText(), sessionId);
            return;
        }
        // normally the join event came first; if it was missed (stream reconnect), the reply also counts as joining
        Handover handover = agentJoined(sessionId, channel, agentName);
        handoverService.agentMessage(handover.getId(), text);
    }

    /** Desk sends the agent's full name; the customer sees the first name, as in Desk's own "Sokha has joined". */
    static String firstName(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "Agent";
        }
        return agentName.trim().split("\\s+")[0];
    }
}
