package com.kbprasac.chatbot.rating.payload.response;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.RatingType;

/** {@code comment} is the stored (masked) text. */
public record RatingResponse(UUID id, RatingType type, int score, String comment, Instant createdAt) {
}
