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
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService.MaskResult;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.AttachmentData;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.ReadStatus;
import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.exception.TooManyRequestsException;
import com.kbprasac.chatbot.shared.repository.AttachmentDataRepository;
import com.kbprasac.chatbot.shared.repository.AttachmentRepository;

/**
 * Customer file uploads: JPG / PNG / PDF up to 5 MB, virus scanned, stored as uploaded, opened only by the agent
 * (every view and delete audited), deleted after the retention period. Files never go to Botpress or Gemini.
 * <ul>
 *   <li>Live agent chat, waiting or active (docs/attachments-simple.md): the file goes to the agent at once.</li>
 *   <li>AI chat (docs/attachments-ai-chat.md): the file is kept as a draft and its text is read on this server and
 *       masked. The customer sees that text, then sends the file with an optional question
 *       ({@link #takeForMessage}); the bot only ever gets the masked text. When the chat later goes to an agent,
 *       the agent gets the file itself.</li>
 * </ul>
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
    static final String AUDIT_DISCARDED = "ATTACHMENT_DISCARDED";
    static final String TARGET = "ATTACHMENT";

    /** OCR uses CPU: one file at a time per chat in the AI chat */
    static final Duration MIN_GAP_BETWEEN_FILES = Duration.ofSeconds(5);
    /** a draft the customer never sent is erased after this */
    static final Duration UNSENT_DRAFT_TTL = Duration.ofHours(1);

    /** A file with its bytes, for the agent's "View". */
    public record StoredFile(Attachment attachment, byte[] data) {
    }

    /** @param draft true: an AI chat draft, waiting for the customer to send it; false: it went to the agent */
    public record Received(Attachment attachment, boolean draft) {
    }

    private final AttachmentRepository attachmentRepository;
    private final AttachmentDataRepository dataRepository;
    private final HandoverService handoverService;
    private final MalwareScanPort scanner;
    private final AuditService audit;
    private final AttachmentProperties props;
    private final FileTextReader textReader;
    private final InputMaskingService masking;

    public AttachmentService(AttachmentRepository attachmentRepository, AttachmentDataRepository dataRepository,
                             HandoverService handoverService, MalwareScanPort scanner, AuditService audit,
                             AttachmentProperties props, FileTextReader textReader, InputMaskingService masking) {
        this.attachmentRepository = attachmentRepository;
        this.dataRepository = dataRepository;
        this.handoverService = handoverService;
        this.scanner = scanner;
        this.audit = audit;
        this.props = props;
        this.textReader = textReader;
        this.masking = masking;
    }

    /** From the widget's 📎 in any mode: to the agent when a live chat is open, otherwise an AI chat draft. */
    @Transactional
    public Received receive(String sessionId, String originalFileName, byte[] data) {
        Optional<Handover> open = handoverService.findOpen(sessionId);
        if (open.isPresent()) {
            return new Received(toAgent(open.get(), sessionId, originalFileName, data), false);
        }
        return new Received(draftForBot(sessionId, originalFileName, data), true);
    }

    /** Live agent chat (waiting or active): the file goes to the agent; 409 when no live chat is open. */
    @Transactional
    public Attachment upload(String sessionId, String originalFileName, byte[] data) {
        Handover handover = handoverService.findOpen(sessionId)
                .orElseThrow(() -> new ConflictException("No live agent chat is open"));
        return toAgent(handover, sessionId, originalFileName, data);
    }

    private Attachment toAgent(Handover handover, String sessionId, String originalFileName, byte[] data) {
        String contentType = check(sessionId, data);
        Instant now = Instant.now();
        Attachment attachment = attachmentRepository.save(Attachment.builder()
                .id(UUID.randomUUID())
                .handoverId(handover.getId())
                .sessionId(sessionId)
                .fileName(cleanFileName(originalFileName, contentType))
                .contentType(contentType)
                .sizeBytes(data.length)
                .createdAt(now)
                .sentAt(now)
                .build());
        dataRepository.save(new AttachmentData(attachment.getId(), data));
        // while waiting, Botpress Desk gets it with the other waiting messages when the agent joins
        handoverService.customerAttachment(handover, attachment);
        return attachment;
    }

    /**
     * AI chat: stored as a draft with its text read on this server and masked. Nothing goes to the bot yet.
     * Requirement: the AI must not collect personal data, so the bot never gets the file, only the masked text.
     */
    private Attachment draftForBot(String sessionId, String originalFileName, byte[] data) {
        if (attachmentRepository.countBySessionIdAndReadStatusIsNotNull(sessionId) >= props.maxFilesPerChat()) {
            throw new TooManyRequestsException("At most " + props.maxFilesPerChat() + " files per chat");
        }
        if (attachmentRepository.existsBySessionIdAndReadStatusIsNotNullAndCreatedAtAfter(sessionId,
                Instant.now().minus(MIN_GAP_BETWEEN_FILES))) {
            throw new TooManyRequestsException("Please wait a few seconds between files");
        }
        String contentType = check(sessionId, data);
        FileTextReader.Result read = textReader.read(data, contentType);
        MaskResult masked = read.status() == ReadStatus.READ ? masking.maskFileText(read.text()) : null;

        Attachment attachment = attachmentRepository.save(Attachment.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .fileName(cleanFileName(originalFileName, contentType))
                .contentType(contentType)
                .sizeBytes(data.length)
                .createdAt(Instant.now())
                .readStatus(read.status())
                .readText(masked != null ? masked.text() : null)
                .readMasked(masked != null && masked.changed())
                .build());
        dataRepository.save(new AttachmentData(attachment.getId(), data));
        return attachment;
    }

    /** Size, type (from the content) and virus scan; returns the detected type. */
    private String check(String sessionId, byte[] data) {
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
        return contentType;
    }

    /**
     * The customer pressed Send with an AI chat file: it is now part of the conversation. Sending it again
     * (a retry) returns the same file.
     */
    @Transactional
    public Attachment takeForMessage(String sessionId, UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .filter(a -> a.getSessionId().equals(sessionId) && a.isForBot() && !a.isDeleted())
                .orElseThrow(() -> new NotFoundException("File not found"));
        if (attachment.getSentAt() == null) {
            attachment.setSentAt(Instant.now());
            attachmentRepository.save(attachment);
        }
        return attachment;
    }

    /** An AI chat file sent while a live chat is open (an agent took over meanwhile): it goes to the agent. */
    @Transactional
    public void sendToAgent(Handover handover, Attachment attachment) {
        if (attachment.getHandoverId() != null) {
            return;
        }
        attachment.setHandoverId(handover.getId());
        attachmentRepository.save(attachment);
        handoverService.customerAttachment(handover, attachment);
    }

    /** The customer removed a draft before sending it (e.g. picked the wrong photo). The bytes are erased. */
    @Transactional
    public void discardDraft(String sessionId, UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .filter(a -> a.getSessionId().equals(sessionId) && a.isForBot())
                .orElseThrow(() -> new NotFoundException("File not found"));
        if (attachment.isDeleted()) {
            return;
        }
        if (attachment.getSentAt() != null) {
            throw new ConflictException("The file was already sent");
        }
        erase(attachment, "customer", "CUSTOMER");
        audit.record("customer", AUDIT_DISCARDED, TARGET, attachmentId.toString(), attachment.getFileName());
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

    /** AI chat drafts the customer never sent: erased after an hour. */
    @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 60_000)
    @Transactional
    public int deleteUnsentDrafts() {
        List<Attachment> unsent = attachmentRepository.findByDeletedAtIsNullAndSentAtIsNullAndCreatedAtBefore(
                Instant.now().minus(UNSENT_DRAFT_TTL));
        unsent.forEach(attachment -> erase(attachment, AuditService.SYSTEM, "UNSENT"));
        return unsent.size();
    }

    private Optional<Attachment> findInHandover(UUID handoverId, UUID attachmentId) {
        return attachmentRepository.findById(attachmentId).filter(a -> handoverId.equals(a.getHandoverId()));
    }

    private void erase(Attachment attachment, String by, String reason) {
        if (dataRepository.existsById(attachment.getId())) {
            dataRepository.deleteById(attachment.getId());
        }
        // the masked text goes with the file
        attachment.setReadText(null);
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
