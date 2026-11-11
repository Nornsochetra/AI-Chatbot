package com.kbprasac.chatbot.rating.payload.request;

import com.kbprasac.chatbot.shared.enums.RatingType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RatingRequest(
        /** AGENT rates the session's most recently finished agent chat; AI rates the bot. */
        @NotNull RatingType type,
        @NotNull @Min(1) @Max(5) Integer score,
        @Size(max = 500) String comment) {
}
