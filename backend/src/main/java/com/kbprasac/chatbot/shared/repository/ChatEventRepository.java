package com.kbprasac.chatbot.shared.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kbprasac.chatbot.shared.entity.ChatEvent;

public interface ChatEventRepository extends JpaRepository<ChatEvent, UUID> {

    /** The bot conversation before a handover, shown to the agent. */
    List<ChatEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** A bot reply of this session (read-aloud only works for the session's own replies). */
    Optional<ChatEvent> findByIdAndSessionId(UUID id, String sessionId);

    /** the latest bot turn of a chat (auto-route after repeated failed answers) */
    Optional<ChatEvent> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** retention: chat history older than the cutoff */
    @Modifying
    @Query("delete from ChatEvent m where m.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
