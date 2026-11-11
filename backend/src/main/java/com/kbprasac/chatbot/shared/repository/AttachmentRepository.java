package com.kbprasac.chatbot.shared.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.Attachment;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    /** files still stored that are older than the retention cutoff */
    List<Attachment> findByDeletedAtIsNullAndCreatedAtBefore(Instant cutoff);
}
