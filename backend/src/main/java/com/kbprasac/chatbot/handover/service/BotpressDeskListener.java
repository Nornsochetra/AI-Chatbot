package com.kbprasac.chatbot.handover.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbprasac.chatbot.chat.service.BotConversation;
import com.kbprasac.chatbot.chat.service.BotConversationOpenedEvent;
import com.kbprasac.chatbot.chat.service.ChatSessionStore;
import com.kbprasac.chatbot.config.BotpressProperties;
import com.kbprasac.chatbot.shared.enums.Channel;

import jakarta.annotation.PreDestroy;

/**
 * Botpress Desk mode: keeps the Botpress live stream ({@code GET /conversations/{id}/listen}) open for every chat
 * session, because a Desk agent can join any bot chat, not only escalated ones. One virtual thread per session;
 * it stops once the session has expired and no handover is open.
 * <p>
 * R&D limits: events sent while a stream reconnects are missed (an agent reply still counts as joining), and
 * sessions are in memory, so streams are not resumed after a restart.
 */
@Component
@ConditionalOnProperty(name = "app.handover.inbox", havingValue = "botpress-desk")
public class BotpressDeskListener {

    private static final Logger log = LoggerFactory.getLogger(BotpressDeskListener.class);
    private static final Duration RETRY = Duration.ofSeconds(3);

    private final BotpressDeskEvents deskEvents;
    private final HandoverService handoverService;
    private final ChatSessionStore sessionStore;
    private final BotpressProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Thread> watches = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public BotpressDeskListener(BotpressDeskEvents deskEvents, HandoverService handoverService,
                                ChatSessionStore sessionStore, BotpressProperties props, ObjectMapper json) {
        this.deskEvents = deskEvents;
        this.handoverService = handoverService;
        this.sessionStore = sessionStore;
        this.props = props;
        this.json = json;
    }

    @EventListener
    public void onConversationOpened(BotConversationOpenedEvent event) {
        String sessionId = event.sessionId();
        watches.compute(sessionId, (id, previous) -> {
            if (previous != null) {
                previous.interrupt();
            }
            return Thread.ofVirtual().name("desk-listen-" + id)
                    .start(() -> listen(sessionId, event.channel(), event.conversation()));
        });
    }

    private void listen(String sessionId, Channel channel, BotConversation conversation) {
        URI uri = URI.create(props.baseUrl() + "/conversations/" + conversation.conversationId() + "/listen");
        try {
            while (!stopping && stillNeeded(sessionId, conversation)) {
                try {
                    if (readStream(uri, sessionId, channel, conversation)) {
                        return;
                    }
                } catch (IOException e) {
                    log.warn("Botpress stream for session {} dropped: {}", sessionId, e.toString());
                }
                Thread.sleep(RETRY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            watches.remove(sessionId, Thread.currentThread());
        }
    }

    /** Reads one connection until it ends. Returns true when the watch is no longer needed. */
    private boolean readStream(URI uri, String sessionId, Channel channel, BotConversation conversation)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("x-user-key", conversation.userKey())
                .header("Accept", "text/event-stream")
                .GET().build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                log.warn("Botpress stream for session {} answered HTTP {}", sessionId, response.statusCode());
                return false;
            }
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    return true;
                }
                String line = it.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                // Botpress sends "data: ping" every ~30 s: a chance to stop idle watches
                if ("ping".equals(data)) {
                    if (!stillNeeded(sessionId, conversation)) {
                        return true;
                    }
                    continue;
                }
                handle(sessionId, channel, data);
            }
        }
        return false;
    }

    private void handle(String sessionId, Channel channel, String data) {
        try {
            deskEvents.onEvent(sessionId, channel, json.readTree(data));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not handle a Botpress event for session {}", sessionId, e);
        }
    }

    /** While the session still uses this conversation, or (session expired) its handover is still open. */
    private boolean stillNeeded(String sessionId, BotConversation conversation) {
        return sessionStore.peek(sessionId)
                .map(s -> s.conversation() != null
                        && s.conversation().conversationId().equals(conversation.conversationId()))
                .orElseGet(() -> handoverService.findOpen(sessionId).isPresent());
    }

    @PreDestroy
    void stop() {
        stopping = true;
        watches.values().forEach(Thread::interrupt);
    }
}
