package com.kbprasac.chatbot.shared.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A file a customer uploaded in a live agent chat. Metadata only; the bytes are in {@link AttachmentData}. */
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

    @Column(name = "handover_id", nullable = false)
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

    /** AGENT or RETENTION */
    @Column(name = "delete_reason")
    private String deleteReason;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
