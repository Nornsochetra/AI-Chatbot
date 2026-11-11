package com.kbprasac.chatbot.chat.payload.response;

import com.kbprasac.chatbot.shared.enums.AgentInbox;

public record ChatConfigResponse(
        AgentInbox agentInbox,
        /** Botpress Desk: an agent can join any chat, so the widget keeps its live stream open in bot mode too. */
        boolean agentMayJoinAnytime) {
}
