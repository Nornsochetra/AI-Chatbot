package com.kbprasac.chatbot.audit.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.shared.entity.AuditEvent;
import com.kbprasac.chatbot.shared.repository.AuditEventRepository;

/** Audit log: who did what to which record, and when. Read by the admin portal later. */
@Service
public class AuditService {

    public static final String SYSTEM = "system";

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** Saved in its own transaction, so the entry stays even if the caller fails afterwards. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String targetType, String targetId, String detail) {
        repository.save(AuditEvent.builder()
                .actor(actor)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .createdAt(Instant.now())
                .build());
    }
}
