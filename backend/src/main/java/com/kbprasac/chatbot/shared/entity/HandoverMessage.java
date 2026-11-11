package com.kbprasac.chatbot.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.MessageSender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A message during a handover. The increasing id is the polling cursor for the widget and the agent console. */
@Entity
@Table(name = "handover_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandoverMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handover_id", nullable = false)
    private UUID handoverId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageSender sender;

    @Column(nullable = false)
    private String text;

    /** agent's login username, for agent messages and agent joined / left events; null otherwise */
    @Column(name = "sender_name")
    private String senderName;

    /** the uploaded file, for a customer upload or a "file removed" event; metadata only (no bytes) */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "attachment_id")
    private Attachment attachment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
