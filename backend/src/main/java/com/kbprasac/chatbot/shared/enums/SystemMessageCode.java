package com.kbprasac.chatbot.shared.enums;

/** Handover events. Sent as codes so the widget can show them in the customer's language. */
public enum SystemMessageCode {
    WAITING,
    /** still no agent after the reminder interval: reassure the customer */
    WAITING_REMINDER,
    AGENT_JOINED,
    AGENT_LEFT,
    /** the agent removed a file the customer uploaded */
    ATTACHMENT_REMOVED,
    /** live chat, customer silent for a while: "are you still connected?" before the inactivity close */
    STILL_CONNECTED,
    INACTIVITY_CLOSED,
    /** the customer ended the chat with the agent */
    CUSTOMER_ENDED,
    /** the customer cancelled the request before an agent joined */
    REQUEST_CANCELLED
}
