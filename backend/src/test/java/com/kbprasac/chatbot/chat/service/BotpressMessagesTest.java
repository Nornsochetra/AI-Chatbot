package com.kbprasac.chatbot.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BotpressMessagesTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode message(String metadata) throws Exception {
        return json.readTree("{\"userId\":\"user_bot\",\"payload\":{\"type\":\"text\",\"text\":\"x\"}"
                + (metadata == null ? "" : ",\"metadata\":" + metadata) + "}");
    }

    @Test
    void botAnswerIsTheOnlyKindTheChatFlowShows() throws Exception {
        JsonNode bot = message("{\"streamId\":\"s:llmz_1:item-0\",\"citations\":[]}");
        assertTrue(BotpressMessages.isBotAnswer(bot));
        assertFalse(BotpressMessages.isAgentMessage(bot));
        assertNull(BotpressMessages.hitlKind(bot));
    }

    @Test
    void deskAgentReplyIsNotABotAnswer() throws Exception {
        JsonNode agent = message("{\"adminName\":\"Sokha Chan\"}");
        assertTrue(BotpressMessages.isAgentMessage(agent));
        assertFalse(BotpressMessages.isBotAnswer(agent));
    }

    @Test
    void deskHandoffNoticeIsNotABotAnswer() throws Exception {
        JsonNode notice = message("{\"hitl\":{\"kind\":\"agent_assigned\",\"agentName\":\"Sokha Chan\"}}");
        assertEquals("agent_assigned", BotpressMessages.hitlKind(notice));
        assertFalse(BotpressMessages.isBotAnswer(notice));
    }

    @Test
    void messageWithoutMetadataCountsAsBotAnswer() throws Exception {
        assertTrue(BotpressMessages.isBotAnswer(message(null)));
    }
}
