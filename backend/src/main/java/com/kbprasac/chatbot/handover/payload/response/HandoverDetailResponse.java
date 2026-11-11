package com.kbprasac.chatbot.handover.payload.response;

import java.util.List;

/** Everything the agent needs when opening a handover: the ticket, the bot conversation before it, the live chat. */
public record HandoverDetailResponse(
        HandoverSummaryResponse handover,
        List<TranscriptEntryResponse> botTranscript,
        List<LiveMessageResponse> messages,
        /** null until the customer rates the finished chat */
        HandoverRatingResponse rating) {
}
