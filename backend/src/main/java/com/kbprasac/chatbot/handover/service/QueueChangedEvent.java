package com.kbprasac.chatbot.handover.service;

/** A customer left the waiting queue (an agent accepted, or the chat was closed), so everyone behind moves up. */
public record QueueChangedEvent() {
}
