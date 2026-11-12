package com.kbprasac.chatbot.handover.service;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.kbprasac.chatbot.chat.service.BotpressMessages;
import com.kbprasac.chatbot.config.HandoverProperties;
import com.kbprasac.chatbot.handover.service.BotpressDeskHelpdesk.NotConnected;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

/**
 * Turns Botpress live-stream events into handover changes (Botpress Desk mode).
 * Only {@code message_created} matters (verified 2026-11-11):
 * <ul>
 *   <li>{@code metadata.hitl.kind = agent_assigned} (with {@code agentName}): an agent joined</li>
 *   <li>{@code metadata.adminName}: an agent reply</li>
 *   <li>{@code metadata.hitl.kind = ticket_closed}: the agent closed the ticket → close (the bot answers again)</li>
 * </ul>
 * Desk lets any agent join any conversation, so the assignment rules are applied here (docs/handover.md): an agent's
 * join or reply reaches the customer only when
 * <ol>
 *   <li>the customer has an open request (asked for an agent, not cancelled or ended), unless
 *       {@code app.handover.agent-takeover} is on;</li>
 *   <li>no other agent already has the chat;</li>
 *   <li>the agent is below {@code app.handover.max-chats-per-agent} live chats.</li>
 * </ol>
 * Otherwise the customer sees nothing and the agent gets a "[Not connected]" note in Desk.
 * Ignored: bot answers (our chat flow reads those), {@code ticket_opened}, and Desk's own {@code csat_request}
 * (our rating card is used instead).
 */
@Component
@ConditionalOnProperty(name = "app.handover.inbox", havingValue = "botpress-desk", matchIfMissing = true)
public class BotpressDeskEvents {

    private static final Logger log = LoggerFactory.getLogger(BotpressDeskEvents.class);

    private final HandoverService handoverService;
    private final BotpressDeskHelpdesk helpdesk;
    private final HandoverProperties props;

    public BotpressDeskEvents(HandoverService handoverService, BotpressDeskHelpdesk helpdesk,
                              HandoverProperties props) {
        this.handoverService = handoverService;
        this.helpdesk = helpdesk;
        this.props = props;
    }

    public void onEvent(String sessionId, Channel channel, JsonNode event) {
        if (!"message_created".equals(event.path("type").asText())) {
            return;
        }
        JsonNode message = event.path("data");
        String kind = BotpressMessages.hitlKind(message);
        if ("ticket_closed".equals(kind)) {
            helpdesk.deskTicketClosed(sessionId);
            handoverService.findOpen(sessionId)
                    .ifPresent(h -> handoverService.close(h.getId(), HandoverCloseReason.AGENT_CLOSED));
        } else if ("agent_assigned".equals(kind) || BotpressMessages.isAgentMessage(message)) {
            if (helpdesk.stillOpenAfterOurClose(sessionId)) {
                // the chat was ended on the customer side; do not pull them back in before Desk closes the ticket
                log.info("Desk agent event in session {} ignored: the chat was ended on the customer side", sessionId);
            } else if ("agent_assigned".equals(kind)) {
                connect(sessionId, channel, message.path("metadata").path("hitl").path("agentName").asText(null));
            } else {
                agentReplied(sessionId, channel, message.path("metadata").path("adminName").asText(null),
                        message.path("payload"));
            }
        }
    }

    /** The assignment rules; returns the handover the agent is connected to, or empty when not connected. */
    private Optional<Handover> connect(String sessionId, Channel channel, String fullName) {
        String agentKey = agentKey(fullName);
        String agentName = firstName(fullName);
        Optional<Handover> open = handoverService.findOpen(sessionId);

        if (open.isEmpty()) {
            if (!props.agentTakeover()) {
                log.info("Desk agent in session {} not connected: the customer has not asked for an agent", sessionId);
                helpdesk.notConnected(sessionId, channel, agentKey, NotConnected.NO_REQUEST, null, 0);
                return Optional.empty();
            }
            if (atLimit(agentKey)) {
                helpdesk.notConnected(sessionId, channel, agentKey, NotConnected.AGENT_AT_LIMIT, null,
                        props.maxChatsPerAgent());
                return Optional.empty();
            }
            log.info("Desk agent took over session {} without a customer request", sessionId);
            Handover started = handoverService.startByAgent(sessionId, channel, agentName, agentKey);
            helpdesk.agentTookOver(started);
            return Optional.of(started);
        }

        Handover handover = open.get();
        if (handover.getStatus() == HandoverStatus.ACTIVE) {
            if (isOwner(handover, agentKey, agentName)) {
                return Optional.of(handover);
            }
            log.info("Desk agent in session {} not connected: another agent has the chat", sessionId);
            helpdesk.notConnected(sessionId, channel, agentKey, NotConnected.OTHER_AGENT, handover.getAgentName(), 0);
            return Optional.empty();
        }

        // WAITING: the first agent below the limit gets the chat
        if (atLimit(agentKey)) {
            log.info("Desk agent in session {} not connected: at the limit of {} live chats", sessionId,
                    props.maxChatsPerAgent());
            helpdesk.notConnected(sessionId, channel, agentKey, NotConnected.AGENT_AT_LIMIT, null,
                    props.maxChatsPerAgent());
            return Optional.empty();
        }
        // what the customer wrote while waiting is already in Desk (posted as it came)
        return Optional.of(handoverService.accept(handover.getId(), agentName, agentKey));
    }

    private void agentReplied(String sessionId, Channel channel, String fullName, JsonNode payload) {
        String text = payload.path("text").asText("");
        if (text.isBlank()) {
            log.warn("Desk agent message of type {} in session {} is not text; not shown to the customer",
                    payload.path("type").asText(), sessionId);
            return;
        }
        // normally the join event came first; if it was missed (stream reconnect), or the agent was not connected
        // then (e.g. at the limit, now free), the reply connects them under the same rules
        connect(sessionId, channel, fullName)
                .ifPresent(handover -> handoverService.agentMessage(handover.getId(), text));
    }

    private boolean atLimit(String agentKey) {
        int max = props.maxChatsPerAgent();
        return max > 0 && handoverService.activeChatsOf(agentKey) >= max;
    }

    /** Chats accepted before agents had a key (V8) are matched by the first name. */
    private static boolean isOwner(Handover handover, String agentKey, String agentName) {
        if (handover.getAgentKey() != null) {
            return handover.getAgentKey().equals(agentKey);
        }
        return agentName.equals(handover.getAgentName());
    }

    /**
     * Desk events carry the agent's name, no id: the full name, lower-cased with single spaces, identifies the
     * agent. Two agents with the same full name would count as one; give each a unique name in Desk.
     */
    static String agentKey(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "agent";
        }
        return agentName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Desk sends the agent's full name; the customer sees the first name, as in Desk's own "Sokha has joined". */
    static String firstName(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return "Agent";
        }
        return agentName.trim().split("\\s+")[0];
    }
}
