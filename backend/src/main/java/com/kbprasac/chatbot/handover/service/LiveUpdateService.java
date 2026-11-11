package com.kbprasac.chatbot.handover.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kbprasac.chatbot.handover.mapper.HandoverMapper;
import com.kbprasac.chatbot.handover.payload.response.AgentLiveUpdateResponse;
import com.kbprasac.chatbot.handover.payload.response.SessionUpdatesResponse;
import com.kbprasac.chatbot.handover.payload.response.TypingEventResponse;
import com.kbprasac.chatbot.handover.service.LiveEventHub.Push;
import com.kbprasac.chatbot.handover.service.LiveEventHub.Subscriber;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.exception.ConflictException;

/**
 * Real-time push for live agent chats (Server-Sent Events).
 * Widget: "update" events (same content as the polling endpoint) and "typing" when the agent types.
 * Agent console: "update" events (handover state + new messages) and "typing" when the customer types.
 */
@Service
public class LiveUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateService.class);

    static final String UPDATE = "update";
    static final String TYPING = "typing";

    private final LiveEventHub hub;
    private final HandoverService handoverService;
    private final HandoverMapper mapper;

    public LiveUpdateService(LiveEventHub hub, HandoverService handoverService, HandoverMapper mapper) {
        this.hub = hub;
        this.handoverService = handoverService;
        this.mapper = mapper;
    }

    static String sessionTopic(String sessionId) {
        return "session:" + sessionId;
    }

    static String handoverTopic(UUID handoverId) {
        return "handover:" + handoverId;
    }

    /** Opens the widget's stream; first sends the current state and anything after {@code after}. */
    public SseEmitter subscribeCustomer(String sessionId, long after) {
        String topic = sessionTopic(sessionId);
        Subscriber subscriber = hub.subscribe(topic, after);
        hub.push(topic, subscriber, cursor -> customerUpdate(sessionId, cursor, true));
        return subscriber.emitter();
    }

    /** Opens the agent console's stream for one handover. */
    public SseEmitter subscribeAgent(UUID handoverId, long after) {
        handoverService.get(handoverId); // 404 for an unknown handover
        String topic = handoverTopic(handoverId);
        Subscriber subscriber = hub.subscribe(topic, after);
        hub.push(topic, subscriber, cursor -> agentUpdate(handoverId, cursor, true));
        return subscriber.emitter();
    }

    /** After the change is committed, push it to the open screens of both sides. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onHandoverChanged(HandoverChangedEvent event) {
        try {
            hub.publish(sessionTopic(event.sessionId()), cursor -> customerUpdate(event.sessionId(), cursor, false));
            hub.publish(handoverTopic(event.handoverId()), cursor -> agentUpdate(event.handoverId(), cursor, false));
        } catch (RuntimeException e) {
            // the change itself is saved; screens catch up on their next update or reconnect
            log.warn("Live push failed for handover {}", event.handoverId(), e);
        }
    }

    /** Someone left the queue: send every waiting customer their new place in line. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onQueueChanged(QueueChangedEvent event) {
        try {
            handoverService.queue().stream()
                    .filter(h -> h.getStatus() == HandoverStatus.WAITING)
                    .forEach(h -> hub.publish(sessionTopic(h.getSessionId()),
                            cursor -> customerUpdate(h.getSessionId(), cursor, true)));
        } catch (RuntimeException e) {
            // positions catch up on the next update or reconnect
            log.warn("Queue position push failed", e);
        }
    }

    /** Customer typing → agent console. Ignored when no agent is chatting. */
    public void customerTyping(String sessionId) {
        handoverService.findOpen(sessionId)
                .filter(h -> h.getStatus() == HandoverStatus.ACTIVE)
                .ifPresent(h -> hub.broadcast(handoverTopic(h.getId()), TYPING, new TypingEventResponse("CUSTOMER", null)));
    }

    /** Agent typing → widget, with the agent's login username. */
    public void agentTyping(UUID handoverId) {
        Handover handover = handoverService.get(handoverId);
        if (handover.getStatus() != HandoverStatus.ACTIVE) {
            throw new ConflictException("Handover is " + handover.getStatus());
        }
        hub.broadcast(sessionTopic(handover.getSessionId()), TYPING,
                new TypingEventResponse("AGENT", handover.getAgentName()));
    }

    private Push customerUpdate(String sessionId, long cursor, boolean always) {
        List<HandoverMessage> messages = handoverService.messagesForSession(sessionId, cursor);
        if (messages.isEmpty() && !always) {
            return null;
        }
        Optional<Handover> open = handoverService.findOpen(sessionId);
        SessionUpdatesResponse update = mapper.toUpdates(open, open.map(handoverService::queuePosition).orElse(null),
                messages, cursor);
        return new Push(UPDATE, update, update.cursor());
    }

    private Push agentUpdate(UUID handoverId, long cursor, boolean always) {
        List<HandoverMessage> messages = handoverService.messagesForHandover(handoverId, cursor);
        if (messages.isEmpty() && !always) {
            return null;
        }
        long next = messages.isEmpty() ? cursor : messages.get(messages.size() - 1).getId();
        AgentLiveUpdateResponse update = new AgentLiveUpdateResponse(
                mapper.toSummary(handoverService.get(handoverId)), mapper.toMessages(messages), next);
        return new Push(UPDATE, update, next);
    }
}
