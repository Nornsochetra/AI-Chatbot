package com.kbprasac.chatbot.handover.payload.response;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

/** A handover as the agent console lists it. */
public record HandoverSummaryResponse(
        UUID id,
        String sessionId,
        Channel channel,
        HandoverStatus status,
        HandoverReason reason,
        String agentName,
        Instant createdAt,
        Instant acceptedAt,
        Instant lastCustomerActivityAt,
        Instant closedAt,
        HandoverCloseReason closeReason,
        /** when the chat closes automatically if the customer stays silent; null when auto-close is off or not active */
        Instant autoCloseAt) {
}
