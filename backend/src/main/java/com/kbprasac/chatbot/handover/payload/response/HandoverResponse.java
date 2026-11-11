package com.kbprasac.chatbot.handover.payload.response;

import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;

/** Returned to the widget when the customer asks for an agent. */
public record HandoverResponse(String sessionId, SessionState state, HandoverStatus handoverStatus,
                               /** 1 = next in line; null unless waiting */ Integer queuePosition) {
}
