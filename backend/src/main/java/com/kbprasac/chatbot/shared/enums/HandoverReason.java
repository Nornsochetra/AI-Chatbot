package com.kbprasac.chatbot.shared.enums;

public enum HandoverReason {
    /** the customer pressed "Talk to an agent" */
    USER_REQUEST,
    /** the customer accepted the prompt shown after the bot could not answer */
    BOT_SUGGESTED,
    /** Botpress Desk: an agent joined the chat without the customer asking */
    AGENT_INITIATED,
    /** BRD auto-route: the message shows an urgent need (fraud, lost or stolen card, hacked account) */
    AUTO_URGENT,
    /** BRD auto-route: the customer asked for a person in their message */
    AUTO_ASKED_FOR_AGENT,
    /** BRD auto-route: the bot could not answer twice in a row */
    AUTO_BOT_FAILED;

    public boolean isAutoRouted() {
        return this == AUTO_URGENT || this == AUTO_ASKED_FOR_AGENT || this == AUTO_BOT_FAILED;
    }
}
