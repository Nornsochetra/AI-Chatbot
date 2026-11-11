package com.kbprasac.chatbot.handover.payload.response;

import java.time.Instant;

/** One bot turn before the handover. {@code userText} is already masked. */
public record TranscriptEntryResponse(String userText, String botReply, Instant createdAt) {
}
