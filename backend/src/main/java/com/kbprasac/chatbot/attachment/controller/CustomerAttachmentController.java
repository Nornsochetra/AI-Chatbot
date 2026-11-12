package com.kbprasac.chatbot.attachment.controller;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kbprasac.chatbot.attachment.mapper.AttachmentMapper;
import com.kbprasac.chatbot.attachment.payload.response.AttachmentResponse;
import com.kbprasac.chatbot.attachment.payload.response.FileUploadResponse;
import com.kbprasac.chatbot.attachment.service.AttachmentService;
import com.kbprasac.chatbot.chat.service.ChatSessionStore;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.exception.BadRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Customer side (widget). Public like the chat endpoint. */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Attachments (customer)")
public class CustomerAttachmentController {

    private final AttachmentService attachmentService;
    private final ChatSessionStore sessionStore;
    private final AttachmentMapper mapper;

    public CustomerAttachmentController(AttachmentService attachmentService, ChatSessionStore sessionStore,
                                        AttachmentMapper mapper) {
        this.attachmentService = attachmentService;
        this.sessionStore = sessionStore;
        this.mapper = mapper;
    }

    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Send a JPG / PNG / PDF (max 5 MB, field 'file') from the widget in any mode. AI chat: "
            + "returns a draft with the masked text the bot will read (send it with POST /messages + attachmentId). "
            + "Live agent chat: the file goes to the agent. 429 over the per-chat limit.")
    public FileUploadResponse send(@RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String sessionId,
                                   @RequestParam Channel channel) {
        if (sessionId != null && sessionId.length() > 64) {
            throw new BadRequestException("sessionId is too long");
        }
        String session = sessionStore.resolve(sessionId, channel).sessionId();
        return mapper.toUploadResponse(session, attachmentService.receive(session, file.getOriginalFilename(),
                bytes(file)));
    }

    @PostMapping(path = "/sessions/{sessionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a JPG / PNG / PDF (max 5 MB, field 'file') during a live agent chat (waiting or "
            + "active); 409 without one")
    public AttachmentResponse upload(@PathVariable String sessionId, @RequestPart("file") MultipartFile file) {
        return mapper.toResponse(attachmentService.upload(sessionId, file.getOriginalFilename(), bytes(file)));
    }

    @DeleteMapping("/sessions/{sessionId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove an AI chat file before sending it; 409 once sent")
    public void discard(@PathVariable String sessionId, @PathVariable UUID attachmentId) {
        attachmentService.discardDraft(sessionId, attachmentId);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the file");
        }
    }
}
