package com.kbprasac.chatbot.attachment.payload.response;

import java.time.Instant;
import java.util.UUID;

/** Returned to the widget after an upload. {@code contentType} is the type detected from the file. */
public record AttachmentResponse(UUID id, String fileName, String contentType, int sizeBytes, Instant createdAt) {
}
