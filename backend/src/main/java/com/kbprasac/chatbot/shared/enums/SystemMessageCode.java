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
    INACTIVITY_CLOSED
}
