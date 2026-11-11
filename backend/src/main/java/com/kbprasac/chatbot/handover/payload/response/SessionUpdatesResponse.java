package com.kbprasac.chatbot.handover.payload.response;

import java.util.List;

import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;

/**
 * What the widget polls while a live agent is waiting or active.
 * Pass {@code cursor} back as {@code after} on the next poll.
 */
public record SessionUpdatesResponse(
        SessionState state,
        /** null when no handover is open */
        HandoverStatus handoverStatus,
        /** 1 = next in line; null unless waiting for an agent */
        Integer queuePosition,
        List<LiveMessageResponse> messages,
        long cursor) {
}
