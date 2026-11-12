package com.kbprasac.chatbot.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.Channel;

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

/** One customer turn. Stores the masked text only, never the raw input. */
@Entity
@Table(name = "chat_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "user_text", nullable = false)
    private String userText;

    @Column(name = "bot_reply")
    private String botReply;

    @Column(name = "input_masked", nullable = false)
    private boolean inputMasked;

    @Column(name = "handover_suggested", nullable = false)
    private boolean handoverSuggested;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** the file this turn was about (sent to the AI chat); its masked text is part of userText */
    @Column(name = "attachment_id")
    private UUID attachmentId;
}
