package com.kbprasac.chatbot.shared.enums;

public enum HandoverCloseReason {
    AGENT_CLOSED,
    /** Requirement: no customer message for 2 minutes while an agent is active */
    CUSTOMER_INACTIVE
}
