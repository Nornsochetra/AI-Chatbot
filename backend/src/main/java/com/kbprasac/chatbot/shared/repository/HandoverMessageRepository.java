package com.kbprasac.chatbot.shared.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kbprasac.chatbot.shared.entity.HandoverMessage;

public interface HandoverMessageRepository extends JpaRepository<HandoverMessage, Long> {

    List<HandoverMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(String sessionId, long afterId);

    List<HandoverMessage> findByHandoverIdAndIdGreaterThanOrderByIdAsc(UUID handoverId, long afterId);

    /** retention: chat history older than the cutoff */
    @Modifying
    @Query("delete from HandoverMessage m where m.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
