package com.kbprasac.chatbot.handover.payload.response;

import java.util.List;

/** Pushed to the agent console: the handover's current state and its new messages. */
public record AgentLiveUpdateResponse(HandoverSummaryResponse handover, List<LiveMessageResponse> messages, long cursor) {
}
