package com.kbprasac.chatbot.shared.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.Attachment;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** files still stored that are older than the retention cutoff */
    List<Attachment> findByDeletedAtIsNullAndCreatedAtBefore(Instant cutoff);

    /** AI chat drafts the customer never sent */
    List<Attachment> findByDeletedAtIsNullAndSentAtIsNullAndCreatedAtBefore(Instant cutoff);

    /** files sent to the AI chat in this session (limit per chat) */
    long countBySessionIdAndReadStatusIsNotNull(String sessionId);

    /** a file sent to the AI chat very recently (OCR uses CPU, so one at a time) */
    boolean existsBySessionIdAndReadStatusIsNotNullAndCreatedAtAfter(String sessionId, Instant after);

    /** files sent to the AI chat that no agent has been given yet, oldest first */
    List<Attachment> findBySessionIdAndHandoverIdIsNullAndSentAtIsNotNullAndDeletedAtIsNullOrderBySentAtAsc(
            String sessionId);
}
