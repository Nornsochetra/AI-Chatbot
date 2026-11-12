package com.kbprasac.chatbot.chat.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.audit.service.AuditService;
import com.kbprasac.chatbot.config.ChatProperties;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;
import com.kbprasac.chatbot.shared.repository.HandoverMessageRepository;

/**
 * BRD retention and deletion: chat history (bot turns and live chat messages, already masked) is deleted after
 * {@code app.chat.history-retention-days}. Handover records (times, status, agent) and ratings stay for reporting;
 * files have their own retention (docs/attachments-simple.md). Botpress keeps its own copy of bot conversations:
 * its retention is a vendor setting (BRD VI.B).
 */
@Component
public class ChatHistoryRetention {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryRetention.class);

    static final String AUDIT_EXPIRED = "CHAT_HISTORY_EXPIRED";

    private final ChatEventRepository chatEvents;
    private final HandoverMessageRepository handoverMessages;
    private final AuditService audit;
    private final ChatProperties props;

    public ChatHistoryRetention(ChatEventRepository chatEvents, HandoverMessageRepository handoverMessages,
                                AuditService audit, ChatProperties props) {
        this.chatEvents = chatEvents;
        this.handoverMessages = handoverMessages;
        this.audit = audit;
        this.props = props;
    }

    /** Every night, after the file retention. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public int deleteExpired() {
        if (props.historyRetentionDays() <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.historyRetentionDays()));
        int deleted = chatEvents.deleteCreatedBefore(cutoff) + handoverMessages.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            log.info("Retention: deleted {} chat messages older than {} days", deleted, props.historyRetentionDays());
            audit.record(AuditService.SYSTEM, AUDIT_EXPIRED, "CHAT_HISTORY", null,
                    deleted + " messages older than " + props.historyRetentionDays() + " days");
        }
        return deleted;
    }
}
