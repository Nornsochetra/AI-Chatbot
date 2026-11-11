package com.kbprasac.chatbot.chat.payload.request;

import com.kbprasac.chatbot.shared.enums.Channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
        /** Null on the first message; the response returns the id to reuse. */
        @Size(max = 64) String sessionId,
        @NotNull Channel channel,
        @NotBlank @Size(max = 2000) String text) {
}
