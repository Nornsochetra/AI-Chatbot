package com.kbprasac.chatbot.handover.controller;

import java.security.Principal;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kbprasac.chatbot.handover.payload.response.CustomerMessageResponse;
import com.kbprasac.chatbot.handover.service.AgentMessageService;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Agent side: the secure message page Botpress Desk links to. Requires the AGENT role; every view is audited. */
@RestController
@RequestMapping("/api/v1/agent/handovers/{handoverId}/messages/{messageId}")
@Tag(name = "Messages (agent)")
public class AgentMessageController {

    private final AgentMessageService messageService;

    public AgentMessageController(AgentMessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    @Operation(summary = "A customer's live chat message with the personal data Botpress does not get (audited)")
    public ResponseEntity<CustomerMessageResponse> view(@PathVariable UUID handoverId, @PathVariable long messageId,
                                                        Principal agent) {
        HandoverMessage message = messageService.openForAgent(handoverId, messageId, agent.getName());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CustomerMessageResponse(message.getText(), message.getCreatedAt()));
    }
}
