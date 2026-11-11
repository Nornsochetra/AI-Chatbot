package com.kbprasac.chatbot.handover.payload.response;

import java.time.Instant;

/** The customer's rating of a finished agent chat, shown in the agent console. {@code comment} is masked. */
public record HandoverRatingResponse(int score, String comment, Instant createdAt) {
}
