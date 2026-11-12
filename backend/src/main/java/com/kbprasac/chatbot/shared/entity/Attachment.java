package com.kbprasac.chatbot.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.ReadStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file a customer uploaded, in a live agent chat or while chatting with the AI. Metadata only; the bytes are
 * in {@link AttachmentData}.
 */
@Entity
@Table(name = "attachment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    @Id
    private UUID id;

    /** null while the file only went to the AI chat; set when the chat goes to an agent */
    @Column(name = "handover_id")
    private UUID handoverId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    /** label only, never used as a path */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** detected from the file content, not taken from the browser */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    /** AGENT, RETENTION, CUSTOMER (draft removed) or UNSENT (draft never sent) */
    @Column(name = "delete_reason")
    private String deleteReason;

    /** when it went into the conversation; null = a draft in the AI chat the customer has not sent yet */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** set for files sent to the AI chat; null for live agent chat files (never read) */
    @Enumerated(EnumType.STRING)
    @Column(name = "read_status")
    private ReadStatus readStatus;

    /** the text the bot gets, already masked; the raw text is never stored */
    @Column(name = "read_text")
    private String readText;

    /** masking changed the read text */
    @Column(name = "read_masked", nullable = false)
    private boolean readMasked;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isImage() {
        return contentType.startsWith("image/");
    }

    /** a file sent while chatting with the AI (not in a live agent chat) */
    public boolean isForBot() {
        return readStatus != null;
    }
}
