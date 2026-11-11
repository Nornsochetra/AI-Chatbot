package com.kbprasac.chatbot.shared.enums;

public enum HandoverReason {
    /** the customer pressed "Talk to an agent" */
    USER_REQUEST,
    /** the customer accepted the prompt shown after the bot could not answer */
    BOT_SUGGESTED,
    /** Botpress Desk: an agent joined the chat without the customer asking */
    AGENT_INITIATED
}
