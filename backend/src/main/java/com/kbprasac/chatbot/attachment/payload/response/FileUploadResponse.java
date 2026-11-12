package com.kbprasac.chatbot.attachment.payload.response;

import com.kbprasac.chatbot.shared.enums.ReadStatus;

/**
 * Returned for a file sent from the widget's 📎 (docs/attachments-ai-chat.md).
 *
 * @param sessionId  the chat session (created when the file is the first thing the customer sends)
 * @param draft      true: AI chat, the customer still has to press Send (with an optional question);
 *                   false: a live agent chat is open and the file went to the agent
 * @param readStatus AI chat only: what came of reading the text
 * @param text       AI chat only: the masked text the bot will get, shown to the customer before sending
 * @param textMasked masking hid something in that text
 */
public record FileUploadResponse(String sessionId, AttachmentResponse attachment, boolean draft,
                                 ReadStatus readStatus, String text, boolean textMasked) {
}
