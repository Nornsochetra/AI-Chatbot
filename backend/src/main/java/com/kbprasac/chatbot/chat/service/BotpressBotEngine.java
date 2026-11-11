package com.kbprasac.chatbot.chat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.kbprasac.chatbot.config.BotpressProperties;

/**
 * Botpress Chat API adapter.
 * TODO(verify): request and response shapes below follow the Botpress Chat API as we understand it
 * (POST /users, POST /conversations, POST /messages, GET /conversations/{id}/messages,
 * header x-user-key). Confirm against the current Botpress docs during the first integration test.
 */
@Component
public class BotpressBotEngine implements BotEnginePort {

    private static final String USER_KEY_HEADER = "x-user-key";

    private final RestClient client;
    private final BotpressProperties props;

    public BotpressBotEngine(RestClient botpressRestClient, BotpressProperties props) {
        this.client = botpressRestClient;
        this.props = props;
    }

    @Override
    public BotConversation openConversation() {
        JsonNode user = client.post().uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().body(JsonNode.class);
        String userId = user.path("user").path("id").asText();
        String userKey = user.path("key").asText();

        JsonNode conv = client.post().uri("/conversations")
                .header(USER_KEY_HEADER, userKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve().body(JsonNode.class);
        String conversationId = conv.path("conversation").path("id").asText();

        return new BotConversation(userId, userKey, conversationId);
    }

    @Override
    public Optional<String> ask(BotConversation c, String text) {
        Instant sentAt = Instant.parse(post(c, text).path("message").path("createdAt").asText());

        long deadline = System.currentTimeMillis() + props.replyTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            sleep(props.pollIntervalMs());
            List<String> replies = botRepliesSince(c, sentAt);
            if (!replies.isEmpty()) {
                return Optional.of(String.join("\n", replies));
            }
        }
        return Optional.empty();
    }

    @Override
    public void send(BotConversation c, String text) {
        post(c, text);
    }

    private JsonNode post(BotConversation c, String text) {
        return client.post().uri("/messages")
                .header(USER_KEY_HEADER, c.userKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "conversationId", c.conversationId(),
                        "payload", Map.of("type", "text", "text", text)))
                .retrieve().body(JsonNode.class);
    }

    private List<String> botRepliesSince(BotConversation c, Instant since) {
        JsonNode body = client.get().uri("/conversations/{id}/messages", c.conversationId())
                .header(USER_KEY_HEADER, c.userKey())
                .retrieve().body(JsonNode.class);
        List<String> replies = new ArrayList<>();
        for (JsonNode m : body.path("messages")) {
            // Botpress Desk agent replies and handoff notices also use the bot's user id; they are not answers
            boolean fromBot = !c.userId().equals(m.path("userId").asText()) && BotpressMessages.isBotAnswer(m);
            boolean isNew = Instant.parse(m.path("createdAt").asText()).isAfter(since);
            JsonNode payload = m.path("payload");
            if (fromBot && isNew && "text".equals(payload.path("type").asText())) {
                replies.add(payload.path("text").asText());
            }
        }
        // the API returns newest first
        java.util.Collections.reverse(replies);
        return replies;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the bot reply", e);
        }
    }
}
