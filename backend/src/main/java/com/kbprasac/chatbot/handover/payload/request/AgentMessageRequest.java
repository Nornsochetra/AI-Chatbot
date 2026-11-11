package com.kbprasac.chatbot.handover.payload.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentMessageRequest(@NotBlank @Size(max = 2000) String text) {
}
