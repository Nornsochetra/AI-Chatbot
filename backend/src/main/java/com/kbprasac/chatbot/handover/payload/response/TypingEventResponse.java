package com.kbprasac.chatbot.handover.payload.response;

/**
 * "Someone is typing", pushed to the other side. {@code who}: AGENT or CUSTOMER.
 * {@code name}: the agent's login username (null for the customer). The receiver hides it after a few seconds.
 */
public record TypingEventResponse(String who, String name) {
}
