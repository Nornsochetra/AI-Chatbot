package com.kbprasac.chatbot.handover.payload.response;

import java.time.Instant;

/** A customer's live chat message for the agent's secure page: secrets masked, personal data visible. */
public record CustomerMessageResponse(String text, Instant sentAt) {
}
