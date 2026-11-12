package com.kbprasac.chatbot.handover.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.audit.service.AuditService;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.MessageSender;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.repository.HandoverMessageRepository;

/**
 * A customer's live chat message as the agent may see it, for the secure page Botpress Desk links to when masking
 * hid personal data (account, card, phone, ID numbers) from Botpress. The text is as stored: secrets (OTP, PIN, CVV,
 * password) were masked before storing and stay hidden. Every view is audited; the message is gone once the chat
 * history retention deleted it.
 */
@Service
public class AgentMessageService {

    static final String AUDIT_VIEWED = "MESSAGE_VIEWED";
    static final String TARGET = "HANDOVER_MESSAGE";

    private final HandoverMessageRepository messageRepository;
    private final AuditService audit;

    public AgentMessageService(HandoverMessageRepository messageRepository, AuditService audit) {
        this.messageRepository = messageRepository;
        this.audit = audit;
    }

    /** Only customer text messages of that handover. */
    @Transactional(readOnly = true)
    public HandoverMessage openForAgent(UUID handoverId, long messageId, String agentName) {
        HandoverMessage message = messageRepository.findById(messageId)
                .filter(m -> handoverId.equals(m.getHandoverId()))
                .filter(m -> m.getSender() == MessageSender.CUSTOMER && m.getAttachment() == null)
                .orElseThrow(() -> new NotFoundException("Message not found"));
        audit.record(agentName, AUDIT_VIEWED, TARGET, String.valueOf(messageId), "handover " + handoverId);
        return message;
    }
}
