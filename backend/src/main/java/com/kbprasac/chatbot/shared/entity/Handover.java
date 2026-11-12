package com.kbprasac.chatbot.shared.entity;

import java.time.Instant;
import java.util.UUID;

import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

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

/** A live-agent ticket. The open handover of a session (WAITING or ACTIVE) decides that the bot stays silent. */
@Entity
@Table(name = "handover")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Handover {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HandoverStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HandoverReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason")
    private HandoverCloseReason closeReason;

    /** first name shown to the customer */
    /** first name shown to the customer */
    @Column(name = "agent_name")
    private String agentName;

    /** who the agent is, for the assignment rules: the full Desk name lower-cased, or the console login */
    @Column(name = "agent_key")
    private String agentKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "last_customer_activity_at", nullable = false)
    private Instant lastCustomerActivityAt;

    /** last "still waiting" reminder sent to the customer while no agent had accepted */
    @Column(name = "last_reminder_at")
    private Instant lastReminderAt;
}
