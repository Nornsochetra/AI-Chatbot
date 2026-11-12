package com.kbprasac.chatbot.attachment.mapper;

import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.attachment.payload.response.AttachmentResponse;
import com.kbprasac.chatbot.attachment.payload.response.FileUploadResponse;
import com.kbprasac.chatbot.attachment.service.AttachmentService.Received;
import com.kbprasac.chatbot.shared.entity.Attachment;

@Component
public class AttachmentMapper {

    public AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), attachment.getFileName(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.getCreatedAt());
    }

    public FileUploadResponse toUploadResponse(String sessionId, Received received) {
        Attachment attachment = received.attachment();
        return new FileUploadResponse(sessionId, toResponse(attachment), received.draft(),
                attachment.getReadStatus(), attachment.getReadText(), attachment.isReadMasked());
    }
}
