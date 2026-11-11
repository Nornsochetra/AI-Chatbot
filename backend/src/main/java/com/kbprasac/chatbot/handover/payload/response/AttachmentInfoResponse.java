package com.kbprasac.chatbot.handover.payload.response;

import java.util.UUID;

/** A file in the chat (metadata only). The agent opens it through the agent attachment endpoint. */
public record AttachmentInfoResponse(UUID id, String fileName, String contentType, int sizeBytes, boolean removed) {
}
