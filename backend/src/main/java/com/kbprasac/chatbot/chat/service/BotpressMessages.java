package com.kbprasac.chatbot.chat.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tells Botpress message kinds apart. The bot, Botpress Desk agents and Desk handoff notices all post with the
 * bot's user id; only the metadata differs (verified 2026-11-11, see docs/agent-inbox-switch.md):
 * <ul>
 *   <li>bot answer: {@code metadata.streamId}</li>
 *   <li>Desk agent reply: {@code metadata.adminName}</li>
 *   <li>Desk handoff notice: {@code metadata.hitl.kind} = ticket_opened, agent_assigned, csat_request, ticket_closed</li>
 * </ul>
 */
public final class BotpressMessages {

    private BotpressMessages() {
    }

    public static boolean isAgentMessage(JsonNode message) {
        return message.path("metadata").hasNonNull("adminName");
    }

    /** The Desk handoff event kind, or null for an ordinary message. */
    public static String hitlKind(JsonNode message) {
        JsonNode kind = message.path("metadata").path("hitl").path("kind");
        return kind.isTextual() ? kind.asText() : null;
    }

    /** Neither a Desk agent reply nor a Desk handoff notice. */
    public static boolean isBotAnswer(JsonNode message) {
        return !isAgentMessage(message) && hitlKind(message) == null;
    }
}
