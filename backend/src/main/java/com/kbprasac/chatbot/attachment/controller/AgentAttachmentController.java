package com.kbprasac.chatbot.attachment.controller;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kbprasac.chatbot.attachment.service.AttachmentService;
import com.kbprasac.chatbot.attachment.service.AttachmentService.StoredFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Agent side (console). Requires the AGENT role; every view and delete is written to the audit log. */
@RestController
@RequestMapping("/api/v1/agent/handovers/{handoverId}/attachments/{attachmentId}")
@Tag(name = "Attachments (agent)")
public class AgentAttachmentController {

    private final AttachmentService attachmentService;

    public AgentAttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping
    @Operation(summary = "Open a file of this chat (audited)")
    public ResponseEntity<byte[]> view(@PathVariable UUID handoverId, @PathVariable UUID attachmentId, Principal agent) {
        StoredFile file = attachmentService.openForAgent(handoverId, attachmentId, agent.getName());
        return ResponseEntity.ok()
                // the type detected from the content at upload, never the browser's claim
                .contentType(MediaType.parseMediaType(file.attachment().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.attachment().getFileName(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(file.data());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a file (e.g. a card photo sent by mistake); the customer sees 'file removed' (audited)")
    public void delete(@PathVariable UUID handoverId, @PathVariable UUID attachmentId, Principal agent) {
        attachmentService.deleteByAgent(handoverId, attachmentId, agent.getName());
    }
}
