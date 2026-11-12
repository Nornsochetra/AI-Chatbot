package com.kbprasac.chatbot.chat.payload.response;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;

public record ChatMessageResponse(
        String sessionId,
        /** Null while a live agent is active (the agent replies through the relay). */
        String reply,
        /** Id of the stored bot reply, used for read-aloud. Null when there is no bot reply. */
        UUID replyId,
        SessionState state,
        boolean handoverSuggested,
        boolean inputMasked,
        Instant createdAt,
        /** WAITING when this message opened a live agent request (auto-route); otherwise null */
        HandoverStatus handoverStatus) {
}
