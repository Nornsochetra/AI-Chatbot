package com.kbprasac.chatbot.shared.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.ChatEvent;

public interface ChatEventRepository extends JpaRepository<ChatEvent, UUID> {

    /** The bot conversation before a handover, shown to the agent. */
    List<ChatEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** A bot reply of this session (read-aloud only works for the session's own replies). */
    Optional<ChatEvent> findByIdAndSessionId(UUID id, String sessionId);
}
