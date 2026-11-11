package com.kbprasac.chatbot.attachment.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kbprasac.chatbot.attachment.mapper.AttachmentMapper;
import com.kbprasac.chatbot.attachment.payload.response.AttachmentResponse;
import com.kbprasac.chatbot.attachment.service.AttachmentService;
import com.kbprasac.chatbot.shared.exception.BadRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Customer side (widget). Public like the chat endpoint; only accepted while an agent is chatting. */
@RestController
@RequestMapping("/api/v1/chat/sessions/{sessionId}/attachments")
@Tag(name = "Attachments (customer)")
public class CustomerAttachmentController {

    private final AttachmentService attachmentService;
    private final AttachmentMapper mapper;

    public CustomerAttachmentController(AttachmentService attachmentService, AttachmentMapper mapper) {
        this.attachmentService = attachmentService;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a JPG / PNG / PDF (max 5 MB, field 'file') during a live agent chat; 409 without an agent")
    public AttachmentResponse upload(@PathVariable String sessionId, @RequestPart("file") MultipartFile file) {
        try {
            return mapper.toResponse(attachmentService.upload(sessionId, file.getOriginalFilename(), file.getBytes()));
        } catch (IOException e) {
            throw new BadRequestException("Could not read the file");
        }
    }
}
