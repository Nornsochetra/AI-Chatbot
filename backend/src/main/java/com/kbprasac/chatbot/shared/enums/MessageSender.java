package com.kbprasac.chatbot.shared.enums;

public enum MessageSender {
    CUSTOMER,
    AGENT,
    /** an event shown to the customer; the text is a {@link SystemMessageCode} the widget translates */
    SYSTEM
}
