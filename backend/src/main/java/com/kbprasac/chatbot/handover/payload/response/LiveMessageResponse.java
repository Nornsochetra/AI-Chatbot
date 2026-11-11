package com.kbprasac.chatbot.handover.payload.response;

import java.time.Instant;

import com.kbprasac.chatbot.shared.enums.MessageSender;

/**
 * A handover message. For SYSTEM messages, {@code text} is a SystemMessageCode.
 * {@code senderName} is the agent's login username for agent messages and agent events.
 * {@code attachment} is set for a customer upload and for the "file removed" event.
 */
public record LiveMessageResponse(long id, MessageSender sender, String text, String senderName,
                                  AttachmentInfoResponse attachment, Instant createdAt) {
}
