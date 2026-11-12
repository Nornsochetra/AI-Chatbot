package com.kbprasac.chatbot.chat.payload.request;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kbprasac.chatbot.shared.enums.Channel;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
        /** Null on the first message; the response returns the id to reuse. */
        @Size(max = 64) String sessionId,
        @NotNull Channel channel,
        /** May be empty when a file is sent. */
        @Size(max = 2000) String text,
        /** A file sent to the AI chat first (POST /attachments); the bot gets its masked text, never the file. */
        UUID attachmentId) {

    public ChatMessageRequest(String sessionId, Channel channel, String text) {
        this(sessionId, channel, text, null);
    }

    @JsonIgnore
    @AssertTrue(message = "text or attachmentId is required")
    public boolean isTextOrFile() {
        return attachmentId != null || (text != null && !text.isBlank());
    }
}
