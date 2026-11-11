package com.kbprasac.chatbot.handover.service;

import java.util.UUID;

/** Something happened in a handover (new message, joined, closed). Pushed to the open screens after commit. */
public record HandoverChangedEvent(UUID handoverId, String sessionId) {
}
