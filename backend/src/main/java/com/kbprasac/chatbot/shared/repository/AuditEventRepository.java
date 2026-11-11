package com.kbprasac.chatbot.shared.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(String targetType, String targetId);
}
