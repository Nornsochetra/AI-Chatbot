package com.kbprasac.chatbot.chat.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.config.ChatProperties;
import com.kbprasac.chatbot.shared.enums.Channel;

/**
 * Maps our session id to the Botpress conversation.
 * R&D: in-memory, single instance, lost on restart. Replace the internals (Postgres or Redis)
 * if sessions must survive restarts or the app runs on several instances.
 */
@Component
public class ChatSessionStore {

    private record Entry(ChatSession session, Instant expiresAt) {
    }

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ChatProperties props;

    public ChatSessionStore(ChatProperties props) {
        this.props = props;
    }

    /** Returns the session and extends its lifetime (sliding expiry). */
    public Optional<ChatSession> find(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        save(entry.session());
        return Optional.of(entry.session());
    }

    /** Returns the session without extending its lifetime. */
    public Optional<ChatSession> peek(String sessionId) {
        Entry entry = sessions.get(sessionId);
        return entry == null || entry.expiresAt().isBefore(Instant.now()) ? Optional.empty() : Optional.of(entry.session());
    }

    /**
     * Returns the session for this id, creating it when missing. A known id that is not in memory
     * (expired, or lost on restart) is kept, so an open handover in the database still matches it.
     */
    public ChatSession resolve(String sessionId, Channel channel) {
        if (sessionId != null) {
            Optional<ChatSession> existing = find(sessionId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        // the Botpress conversation is opened lazily on the first bot message
        ChatSession created = new ChatSession(sessionId != null ? sessionId : UUID.randomUUID().toString(), channel, null);
        save(created);
        return created;
    }

    public void save(ChatSession session) {
        sessions.put(session.sessionId(), new Entry(session, Instant.now().plus(props.sessionTtl())));
    }

    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        Instant now = Instant.now();
        sessions.values().removeIf(e -> e.expiresAt().isBefore(now));
    }
}
