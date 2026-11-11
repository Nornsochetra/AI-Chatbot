package com.kbprasac.chatbot.handover.controller;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kbprasac.chatbot.chat.service.ChatSession;
import com.kbprasac.chatbot.chat.service.ChatSessionStore;
import com.kbprasac.chatbot.handover.mapper.HandoverMapper;
import com.kbprasac.chatbot.handover.payload.request.HandoverRequest;
import com.kbprasac.chatbot.handover.payload.response.HandoverResponse;
import com.kbprasac.chatbot.handover.payload.response.SessionUpdatesResponse;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.handover.service.LiveUpdateService;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverReason;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Customer side (widget). Public like the chat endpoint: the random session id identifies the chat. */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Handover (customer)")
public class CustomerHandoverController {

    private final HandoverService handoverService;
    private final LiveUpdateService liveUpdates;
    private final ChatSessionStore sessionStore;
    private final HandoverMapper mapper;

    public CustomerHandoverController(HandoverService handoverService, LiveUpdateService liveUpdates,
                                      ChatSessionStore sessionStore, HandoverMapper mapper) {
        this.handoverService = handoverService;
        this.liveUpdates = liveUpdates;
        this.sessionStore = sessionStore;
        this.mapper = mapper;
    }

    @PostMapping("/handover")
    @Operation(summary = "Ask for a live agent; the bot stays silent until the handover closes")
    public HandoverResponse requestHandover(@Valid @RequestBody HandoverRequest request) {
        ChatSession session = sessionStore.resolve(request.sessionId(), request.channel());
        HandoverReason reason = request.reason() != null ? request.reason() : HandoverReason.USER_REQUEST;
        Handover handover = handoverService.start(session.sessionId(), session.channel(), reason);
        return mapper.toResponse(handover, handoverService.queuePosition(handover));
    }

    @GetMapping(path = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Real-time stream (SSE): 'update' (agent messages / events, same as /updates) and 'typing'")
    public SseEmitter events(@PathVariable String sessionId,
                             @RequestParam(defaultValue = "0") long after,
                             @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        // on reconnect the browser sends the last event id it received
        return liveUpdates.subscribeCustomer(sessionId, lastEventId != null ? lastEventId : after);
    }

    @PostMapping("/sessions/{sessionId}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "The customer is typing (shown to the agent); ignored when no agent is chatting")
    public void typing(@PathVariable String sessionId) {
        liveUpdates.customerTyping(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/updates")
    @Operation(summary = "Poll agent messages and handover events after the given cursor")
    public SessionUpdatesResponse updates(@PathVariable String sessionId,
                                          @RequestParam(defaultValue = "0") long after) {
        Optional<Handover> open = handoverService.findOpen(sessionId);
        return mapper.toUpdates(open, open.map(handoverService::queuePosition).orElse(null),
                handoverService.messagesForSession(sessionId, after), after);
    }
}
