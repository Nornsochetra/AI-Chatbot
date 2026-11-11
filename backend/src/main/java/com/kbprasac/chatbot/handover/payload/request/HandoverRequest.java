package com.kbprasac.chatbot.handover.payload.request;

import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record HandoverRequest(
        /** Null if the customer asks for an agent before sending any message. */
        @Size(max = 64) String sessionId,
        @NotNull Channel channel,
        /** Defaults to USER_REQUEST. */
        HandoverReason reason) {
}
