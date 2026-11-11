package com.kbprasac.chatbot.shared.enums;

/** Where live agents work ({@code app.handover.inbox}). */
public enum AgentInbox {
    /** our own agent console at /agent */
    CONSOLE,
    /** Botpress Desk; the backend bridges it to the widget (docs/agent-inbox-switch.md) */
    BOTPRESS_DESK
}
