package com.kbprasac.chatbot.attachment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.audit.service.AuditService;
import com.kbprasac.chatbot.config.AttachmentProperties;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.AttachmentData;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.repository.AttachmentDataRepository;
import com.kbprasac.chatbot.shared.repository.AttachmentRepository;

/**
 * Customer file uploads (docs/attachments-simple.md): only during a live agent chat, JPG / PNG / PDF up to 5 MB,
 * virus scanned, stored as uploaded, opened only by the agent (every view and delete audited), deleted after the
 * retention period. Files never go to Botpress or Gemini.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_FILE_NAME = 120;

    static final String AUDIT_VIEWED = "ATTACHMENT_VIEWED";
    static final String AUDIT_DELETED = "ATTACHMENT_DELETED";
    static final String AUDIT_EXPIRED = "ATTACHMENT_EXPIRED";
    static final String AUDIT_VIRUS = "ATTACHMENT_REJECTED_VIRUS";
    static final String TARGET = "ATTACHMENT";

    /** A file with its bytes, for the agent's "View". */
    public record StoredFile(Attachment attachment, byte[] data) {
    }

    private final AttachmentRepository attachmentRepository;
    private final AttachmentDataRepository dataRepository;
    private final HandoverService handoverService;
    private final MalwareScanPort scanner;
    private final AuditService audit;
    private final AttachmentProperties props;

    public AttachmentService(AttachmentRepository attachmentRepository, AttachmentDataRepository dataRepository,
                             HandoverService handoverService, MalwareScanPort scanner, AuditService audit,
                             AttachmentProperties props) {
        this.attachmentRepository = attachmentRepository;
        this.dataRepository = dataRepository;
        this.handoverService = handoverService;
        this.scanner = scanner;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public Attachment upload(String sessionId, String originalFileName, byte[] data) {
        // Requirement: the AI must not collect personal data, so files are only accepted once an agent has joined
        Handover handover = handoverService.findOpen(sessionId)
                .filter(h -> h.getStatus() == HandoverStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("Files can be shared once an agent has joined the chat"));
        if (data == null || data.length == 0) {
            throw new BadRequestException("Empty file");
        }
        if (data.length > MAX_BYTES) {
            throw new BadRequestException("File is larger than 5 MB");
        }
        String contentType = FileTypeDetector.detect(data)
                .orElseThrow(() -> new BadRequestException("Only JPG, PNG and PDF files are allowed"));
        String threat = scanner.findThreat(data);
        if (threat != null) {
            audit.record(AuditService.SYSTEM, AUDIT_VIRUS, "SESSION", sessionId, threat);
            throw new BadRequestException("The file was rejected by the virus scan");
        }

        Attachment attachment = attachmentRepository.save(Attachment.builder()
                .id(UUID.randomUUID())
                .handoverId(handover.getId())
                .sessionId(sessionId)
                .fileName(cleanFileName(originalFileName, contentType))
                .contentType(contentType)
                .sizeBytes(data.length)
                .createdAt(Instant.now())
                .build());
        dataRepository.save(new AttachmentData(attachment.getId(), data));
        handoverService.customerAttachment(handover, attachment);
        return attachment;
    }

    /** The agent's "View": only files of that handover that still exist. Every view is audited. */
    @Transactional(readOnly = true)
    public StoredFile openForAgent(UUID handoverId, UUID attachmentId, String agentName) {
        Attachment attachment = findInHandover(handoverId, attachmentId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new NotFoundException("File not found"));
        byte[] data = dataRepository.findById(attachmentId)
                .map(AttachmentData::getData)
                .orElseThrow(() -> new NotFoundException("File not found"));
        audit.record(agentName, AUDIT_VIEWED, TARGET, attachmentId.toString(), attachment.getFileName());
        return new StoredFile(attachment, data);
    }

    /** The agent's "Delete" (e.g. a card photo sent by mistake). The bytes are erased; both sides see it. */
    @Transactional
    public void deleteByAgent(UUID handoverId, UUID attachmentId, String agentName) {
        Attachment attachment = findInHandover(handoverId, attachmentId)
                .orElseThrow(() -> new NotFoundException("File not found"));
        if (attachment.isDeleted()) {
            return;
        }
        erase(attachment, agentName, "AGENT");
        handoverService.attachmentRemoved(handoverService.get(handoverId), attachment);
        audit.record(agentName, AUDIT_DELETED, TARGET, attachmentId.toString(), attachment.getFileName());
    }

    /** Retention: every night, erase files older than the configured number of days. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public int deleteExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.retentionDays()));
        List<Attachment> expired = attachmentRepository.findByDeletedAtIsNullAndCreatedAtBefore(cutoff);
        for (Attachment attachment : expired) {
            erase(attachment, AuditService.SYSTEM, "RETENTION");
            audit.record(AuditService.SYSTEM, AUDIT_EXPIRED, TARGET, attachment.getId().toString(),
                    attachment.getFileName());
        }
        if (!expired.isEmpty()) {
            log.info("Retention: erased {} files older than {} days", expired.size(), props.retentionDays());
        }
        return expired.size();
    }

    private Optional<Attachment> findInHandover(UUID handoverId, UUID attachmentId) {
        return attachmentRepository.findById(attachmentId).filter(a -> a.getHandoverId().equals(handoverId));
    }

    private void erase(Attachment attachment, String by, String reason) {
        if (dataRepository.existsById(attachment.getId())) {
            dataRepository.deleteById(attachment.getId());
        }
        attachment.setDeletedAt(Instant.now());
        attachment.setDeletedBy(by);
        attachment.setDeleteReason(reason);
        attachmentRepository.save(attachment);
    }

    /** The customer's file name is a label only: no folders, no control characters, bounded length. */
    static String cleanFileName(String original, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|*?:]", "").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "file" + extension(contentType);
        }
        // keep the end, so the extension survives
        return name.length() > MAX_FILE_NAME ? name.substring(name.length() - MAX_FILE_NAME) : name;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case FileTypeDetector.JPEG -> ".jpg";
            case FileTypeDetector.PNG -> ".png";
            case FileTypeDetector.PDF -> ".pdf";
            default -> "";
        };
    }
}
