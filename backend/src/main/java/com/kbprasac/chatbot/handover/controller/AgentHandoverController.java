package com.kbprasac.chatbot.handover.controller;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

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

import com.kbprasac.chatbot.config.HandoverProperties;
import com.kbprasac.chatbot.handover.mapper.HandoverMapper;
import com.kbprasac.chatbot.handover.payload.request.AgentMessageRequest;
import com.kbprasac.chatbot.handover.payload.response.HandoverDetailResponse;
import com.kbprasac.chatbot.handover.payload.response.HandoverSummaryResponse;
import com.kbprasac.chatbot.handover.payload.response.LiveMessageResponse;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.handover.service.LiveUpdateService;
import com.kbprasac.chatbot.rating.service.RatingService;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.exception.ConflictException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Agent side (built-in agent console). Requires the AGENT role.
 * In Botpress Desk mode the console is read-only: accept, reply and close happen in Desk.
 */
@RestController
@RequestMapping("/api/v1/agent/handovers")
@Tag(name = "Handover (agent)")
public class AgentHandoverController {

    private final HandoverService handoverService;
    private final LiveUpdateService liveUpdates;
    private final RatingService ratingService;
    private final HandoverMapper mapper;
    private final HandoverProperties props;

    public AgentHandoverController(HandoverService handoverService, LiveUpdateService liveUpdates,
                                   RatingService ratingService, HandoverMapper mapper, HandoverProperties props) {
        this.handoverService = handoverService;
        this.liveUpdates = liveUpdates;
        this.ratingService = ratingService;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Real-time stream (SSE): 'update' (handover state + new messages) and 'typing' (customer)")
    public SseEmitter events(@PathVariable UUID id,
                             @RequestParam(defaultValue = "0") long after,
                             @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        return liveUpdates.subscribeAgent(id, lastEventId != null ? lastEventId : after);
    }

    @PostMapping("/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "The agent is typing; the customer sees '<agent username> is typing…'")
    public void typing(@PathVariable UUID id) {
        liveUpdates.agentTyping(id);
    }

    @GetMapping
    @Operation(summary = "Open handovers (waiting and active), oldest first")
    public List<HandoverSummaryResponse> queue() {
        return handoverService.queue().stream().map(mapper::toSummary).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "A handover with the bot conversation before it and the live messages")
    public HandoverDetailResponse detail(@PathVariable UUID id) {
        Handover handover = handoverService.get(id);
        return mapper.toDetail(handover, handoverService.transcript(handover.getSessionId()),
                handoverService.messagesForHandover(id, 0), ratingService.forHandover(id));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "Poll live messages after the given message id")
    public List<LiveMessageResponse> messages(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after) {
        return mapper.toMessages(handoverService.messagesForHandover(id, after));
    }

    @PostMapping("/{id}/accept")
    @Operation(summary = "Take a waiting handover; the customer sees that an agent joined")
    public HandoverSummaryResponse accept(@PathVariable UUID id, Principal agent) {
        requireConsoleInbox();
        return mapper.toSummary(handoverService.accept(id, agent.getName()));
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Reply to the customer")
    public LiveMessageResponse reply(@PathVariable UUID id, @Valid @RequestBody AgentMessageRequest request) {
        requireConsoleInbox();
        return mapper.toMessage(handoverService.agentMessage(id, request.text()));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "End the live chat; the customer goes back to the bot")
    public HandoverSummaryResponse close(@PathVariable UUID id) {
        requireConsoleInbox();
        return mapper.toSummary(handoverService.close(id, HandoverCloseReason.AGENT_CLOSED));
    }

    private void requireConsoleInbox() {
        if (props.deskMode()) {
            throw new ConflictException("Agents use Botpress Desk (app.handover.inbox=botpress-desk); "
                    + "accept, reply and close the chat there");
        }
    }
}
