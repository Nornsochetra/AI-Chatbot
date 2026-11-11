package com.kbprasac.chatbot.attachment.mapper;

import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.attachment.payload.response.AttachmentResponse;
import com.kbprasac.chatbot.shared.entity.Attachment;

@Component
public class AttachmentMapper {

    public AttachmentResponse toResponse(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), attachment.getFileName(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.getCreatedAt());
    }
}
