package com.kbprasac.chatbot.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.RatingType;

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

/** A customer's post-chat rating. The comment is stored masked. */
@Entity
@Table(name = "rating")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rating {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RatingType type;

    @Column(name = "handover_id")
    private UUID handoverId;

    @Column(nullable = false)
    private short score;

    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
