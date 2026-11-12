package com.kbprasac.chatbot.shared.enums;

public enum HandoverCloseReason {
    AGENT_CLOSED,
    /** Requirement: no customer message for 2 minutes while an agent is active */
    CUSTOMER_INACTIVE,
    /** The customer tapped "End chat" (with an agent) or "Cancel request" (still waiting) in the widget */
    CUSTOMER_ENDED
}
