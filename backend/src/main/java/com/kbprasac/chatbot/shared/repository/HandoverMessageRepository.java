package com.kbprasac.chatbot.shared.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.HandoverMessage;

public interface HandoverMessageRepository extends JpaRepository<HandoverMessage, Long> {

    List<HandoverMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(String sessionId, long afterId);

    List<HandoverMessage> findByHandoverIdAndIdGreaterThanOrderByIdAsc(UUID handoverId, long afterId);
}
