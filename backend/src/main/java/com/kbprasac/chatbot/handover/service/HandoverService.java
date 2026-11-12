package com.kbprasac.chatbot.handover.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.config.HandoverProperties;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.MessageSender;
import com.kbprasac.chatbot.shared.enums.SystemMessageCode;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.repository.AttachmentRepository;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;
import com.kbprasac.chatbot.shared.repository.HandoverMessageRepository;
import com.kbprasac.chatbot.shared.repository.HandoverRepository;

/**
 * Live-agent handover: WAITING (in the queue) → ACTIVE (agent accepted) → CLOSED (agent closed, or
 * the customer was inactive too long). While a handover is open, the bot stays silent.
 */
@Service
public class HandoverService {

    private static final Logger log = LoggerFactory.getLogger(HandoverService.class);

    static final List<HandoverStatus> OPEN = List.of(HandoverStatus.WAITING, HandoverStatus.ACTIVE);

    /** System messages the bank says in its own voice, without the agent's name. */
    private static final List<String> UNNAMED_CODES = List.of(SystemMessageCode.WAITING.name(),
            SystemMessageCode.WAITING_REMINDER.name(), SystemMessageCode.STILL_CONNECTED.name());

    private final HandoverRepository handoverRepository;
    private final HandoverMessageRepository messageRepository;
    private final ChatEventRepository chatEventRepository;
    private final AttachmentRepository attachmentRepository;
    private final HelpdeskPort helpdesk;
    private final HandoverProperties props;
    private final ApplicationEventPublisher events;

    public HandoverService(HandoverRepository handoverRepository, HandoverMessageRepository messageRepository,
                           ChatEventRepository chatEventRepository, AttachmentRepository attachmentRepository,
                           HelpdeskPort helpdesk, HandoverProperties props, ApplicationEventPublisher events) {
        this.handoverRepository = handoverRepository;
        this.messageRepository = messageRepository;
        this.chatEventRepository = chatEventRepository;
        this.attachmentRepository = attachmentRepository;
        this.helpdesk = helpdesk;
        this.props = props;
        this.events = events;
    }

    /** Opens a handover for the session, or returns the one already open. */
    @Transactional
    public Handover start(String sessionId, Channel channel, HandoverReason reason) {
        return findOpen(sessionId).orElseGet(() -> {
            Instant now = Instant.now();
            Handover handover = handoverRepository.save(Handover.builder()
                    .id(UUID.randomUUID())
                    .sessionId(sessionId)
                    .channel(channel)
                    .status(HandoverStatus.WAITING)
                    .reason(reason)
                    .createdAt(now)
                    .lastCustomerActivityAt(now)
                    .build());
            addSystemMessage(handover, SystemMessageCode.WAITING);
            List<Attachment> earlierFiles = linkFilesSentToBot(handover);
            helpdesk.ticketOpened(handover, transcript(sessionId));
            helpdesk.filesSentBefore(handover, earlierFiles);
            return handover;
        });
    }

    @Transactional(readOnly = true)
    public Optional<Handover> findOpen(String sessionId) {
        return handoverRepository.findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(sessionId, OPEN);
    }

    /**
     * Botpress Desk: an agent joined a bot chat the customer had not escalated. The handover starts as ACTIVE;
     * Desk already has the ticket, so the helpdesk is not told.
     */
    @Transactional
    public Handover startByAgent(String sessionId, Channel channel, String agentName, String agentKey) {
        return findOpen(sessionId).orElseGet(() -> {
            Instant now = Instant.now();
            Handover handover = handoverRepository.save(Handover.builder()
                    .id(UUID.randomUUID())
                    .sessionId(sessionId)
                    .channel(channel)
                    .status(HandoverStatus.ACTIVE)
                    .reason(HandoverReason.AGENT_INITIATED)
                    .agentName(agentName)
                    .agentKey(agentKey)
                    .createdAt(now)
                    .acceptedAt(now)
                    .lastCustomerActivityAt(now)
                    .build());
            addSystemMessage(handover, SystemMessageCode.AGENT_JOINED);
            helpdesk.filesSentBefore(handover, linkFilesSentToBot(handover));
            return handover;
        });
    }

    /**
     * Files the customer sent to the AI chat before the handover go to the agent: linked to the handover and
     * added as customer file messages. The agent gets the file; the bot only ever had its masked text.
     */
    private List<Attachment> linkFilesSentToBot(Handover handover) {
        List<Attachment> files = attachmentRepository
                .findBySessionIdAndHandoverIdIsNullAndSentAtIsNotNullAndDeletedAtIsNullOrderBySentAtAsc(
                        handover.getSessionId());
        for (Attachment attachment : files) {
            attachment.setHandoverId(handover.getId());
            attachmentRepository.save(attachment);
            addMessage(handover, MessageSender.CUSTOMER, attachment.getFileName(), attachment);
        }
        return files;
    }

    @Transactional
    public void customerMessage(Handover handover, String maskedText) {
        helpdesk.customerMessage(handover, recordCustomerMessage(handover, maskedText));
    }

    /** A customer message the agent already received another way (Botpress Desk shows the bot conversation). */
    @Transactional
    public HandoverMessage recordCustomerMessage(Handover handover, String maskedText) {
        handover.setLastCustomerActivityAt(Instant.now());
        handoverRepository.save(handover);
        return addMessage(handover, MessageSender.CUSTOMER, maskedText);
    }

    /** The customer uploaded a file; it appears in the chat as a message pointing at the attachment. */
    @Transactional
    public HandoverMessage customerAttachment(Handover handover, Attachment attachment) {
        handover.setLastCustomerActivityAt(Instant.now());
        handoverRepository.save(handover);
        HandoverMessage message = addMessage(handover, MessageSender.CUSTOMER, attachment.getFileName(), attachment);
        helpdesk.customerAttachment(handover, attachment);
        return message;
    }

    /** The agent removed a file; both sides see a "file removed" event pointing at it. */
    @Transactional
    public void attachmentRemoved(Handover handover, Attachment attachment) {
        addMessage(handover, MessageSender.SYSTEM, SystemMessageCode.ATTACHMENT_REMOVED.name(), attachment);
    }

    @Transactional
    public Handover accept(UUID handoverId, String agentName) {
        return accept(handoverId, agentName, agentName);
    }

    /** @param agentKey who the agent is (assignment rules); agentName is the name the customer sees */
    @Transactional
    public Handover accept(UUID handoverId, String agentName, String agentKey) {
        Handover handover = get(handoverId);
        if (handover.getStatus() != HandoverStatus.WAITING) {
            throw new ConflictException("Handover is " + handover.getStatus() + ", only WAITING can be accepted");
        }
        Instant now = Instant.now();
        handover.setStatus(HandoverStatus.ACTIVE);
        handover.setAgentName(agentName);
        handover.setAgentKey(agentKey);
        handover.setAcceptedAt(now);
        // the inactivity timer starts when the agent joins, not when the customer asked
        handover.setLastCustomerActivityAt(now);
        handoverRepository.save(handover);
        addSystemMessage(handover, SystemMessageCode.AGENT_JOINED);
        events.publishEvent(new QueueChangedEvent());
        return handover;
    }

    @Transactional
    public HandoverMessage agentMessage(UUID handoverId, String text) {
        Handover handover = get(handoverId);
        if (handover.getStatus() != HandoverStatus.ACTIVE) {
            throw new ConflictException("Handover is " + handover.getStatus() + ", accept it before replying");
        }
        return addMessage(handover, MessageSender.AGENT, text);
    }

    /** Closes the handover; the session goes back to the bot. Closing twice is harmless. */
    @Transactional
    public Handover close(UUID handoverId, HandoverCloseReason reason) {
        Handover handover = get(handoverId);
        if (handover.getStatus() == HandoverStatus.CLOSED) {
            return handover;
        }
        boolean wasWaiting = handover.getStatus() == HandoverStatus.WAITING;
        handover.setStatus(HandoverStatus.CLOSED);
        handover.setCloseReason(reason);
        handover.setClosedAt(Instant.now());
        handoverRepository.save(handover);
        addSystemMessage(handover, switch (reason) {
            case CUSTOMER_INACTIVE -> SystemMessageCode.INACTIVITY_CLOSED;
            case CUSTOMER_ENDED -> wasWaiting ? SystemMessageCode.REQUEST_CANCELLED : SystemMessageCode.CUSTOMER_ENDED;
            case AGENT_CLOSED -> SystemMessageCode.AGENT_LEFT;
        });
        helpdesk.ticketClosed(handover);
        if (wasWaiting) {
            events.publishEvent(new QueueChangedEvent());
        }
        return handover;
    }

    /** The customer ended the live chat (or cancelled the request) from the widget. Nothing open: nothing to do. */
    @Transactional
    public void endByCustomer(String sessionId) {
        findOpen(sessionId).ifPresent(h -> close(h.getId(), HandoverCloseReason.CUSTOMER_ENDED));
    }

    /**
     * Our side closed the chat but the agent inbox still holds it (Botpress Desk keeps the bot silent until the
     * agent closes the ticket there).
     */
    public boolean agentInboxStillOpen(String sessionId) {
        return helpdesk.stillOpenAfterOurClose(sessionId);
    }

    /** An agent sits in the chat in the inbox without being connected, which pauses the bot (Botpress Desk). */
    public boolean botPausedByInbox(String sessionId) {
        return helpdesk.botPausedByInbox(sessionId);
    }

    /** Live chats this agent has now (assignment rule). */
    @Transactional(readOnly = true)
    public long activeChatsOf(String agentKey) {
        return handoverRepository.countByStatusAndAgentKey(HandoverStatus.ACTIVE, agentKey);
    }

    /** 1 = next in line. Null when the handover is not waiting (an agent has it, or it is closed). */
    @Transactional(readOnly = true)
    public Integer queuePosition(Handover handover) {
        if (handover.getStatus() != HandoverStatus.WAITING) {
            return null;
        }
        return (int) handoverRepository.countByStatusAndCreatedAtBefore(HandoverStatus.WAITING, handover.getCreatedAt()) + 1;
    }

    /**
     * While no agent has accepted, reassure the customer every {@code app.handover.waiting-reminder}
     * ("our agents are still busy..."). Stops as soon as an agent accepts.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @Transactional
    public void remindWaiting() {
        Duration interval = props.waitingReminder();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        Instant now = Instant.now();
        for (Handover handover : handoverRepository.findByStatus(HandoverStatus.WAITING)) {
            Instant last = handover.getLastReminderAt() != null ? handover.getLastReminderAt() : handover.getCreatedAt();
            if (last.plus(interval).isAfter(now)) {
                continue;
            }
            handover.setLastReminderAt(now);
            handoverRepository.save(handover);
            addSystemMessage(handover, SystemMessageCode.WAITING_REMINDER);
        }
    }

    /**
     * BRD message customization: before the inactivity close, ask a silent customer "are you still connected?"
     * (after {@code app.handover.still-there-after}, default 1 minute). Asked once per silence: a customer message
     * resets the timer, and the next silence asks again. {@code last_reminder_at} keeps when it was asked; the
     * queue reminders that used it while waiting all come before the agent joined.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @Transactional
    public void askStillThere() {
        if (!props.asksStillThere()) {
            return;
        }
        Instant now = Instant.now();
        Instant cutoff = now.minus(props.stillThereAfter());
        for (Handover handover : handoverRepository.findByStatusAndLastCustomerActivityAtBefore(HandoverStatus.ACTIVE, cutoff)) {
            Instant asked = handover.getLastReminderAt();
            if (asked != null && asked.isAfter(handover.getLastCustomerActivityAt())) {
                continue;
            }
            handover.setLastReminderAt(now);
            handoverRepository.save(handover);
            addSystemMessage(handover, SystemMessageCode.STILL_CONNECTED);
        }
    }

    /**
     * Requirement: after 2 minutes without a customer message, close the live chat and return to the bot.
     * Only when {@code app.handover.auto-close-enabled} is on; otherwise only the agent closes the chat.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @Transactional
    public void closeInactive() {
        if (!props.autoCloseEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(props.inactivityTimeout());
        for (Handover handover : handoverRepository.findByStatusAndLastCustomerActivityAtBefore(HandoverStatus.ACTIVE, cutoff)) {
            log.info("Closing handover {}: no customer message since {}", handover.getId(),
                    handover.getLastCustomerActivityAt());
            close(handover.getId(), HandoverCloseReason.CUSTOMER_INACTIVE);
        }
    }

    @Transactional(readOnly = true)
    public Handover get(UUID handoverId) {
        return handoverRepository.findById(handoverId)
                .orElseThrow(() -> new NotFoundException("Handover " + handoverId + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Handover> queue() {
        return handoverRepository.findByStatusInOrderByCreatedAtAsc(OPEN);
    }

    @Transactional(readOnly = true)
    public List<HandoverMessage> messagesForSession(String sessionId, long afterId) {
        return messageRepository.findBySessionIdAndIdGreaterThanOrderByIdAsc(sessionId, afterId);
    }

    @Transactional(readOnly = true)
    public List<HandoverMessage> messagesForHandover(UUID handoverId, long afterId) {
        return messageRepository.findByHandoverIdAndIdGreaterThanOrderByIdAsc(handoverId, afterId);
    }

    /** The bot conversation before the handover (masked text only), so the agent has the context. */
    @Transactional(readOnly = true)
    public List<ChatEvent> transcript(String sessionId) {
        return chatEventRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    private void addSystemMessage(Handover handover, SystemMessageCode code) {
        addMessage(handover, MessageSender.SYSTEM, code.name(), null);
    }

    private HandoverMessage addMessage(Handover handover, MessageSender sender, String text) {
        return addMessage(handover, sender, text, null);
    }

    /** Every change in a handover creates a message, so this is also where open screens get notified. */
    private HandoverMessage addMessage(Handover handover, MessageSender sender, String text, Attachment attachment) {
        HandoverMessage message = messageRepository.save(HandoverMessage.builder()
                .handoverId(handover.getId())
                .sessionId(handover.getSessionId())
                .sender(sender)
                .text(text)
                .senderName(namedAfterAgent(sender, text) ? handover.getAgentName() : null)
                .attachment(attachment)
                .createdAt(Instant.now())
                .build());
        events.publishEvent(new HandoverChangedEvent(handover.getId(), handover.getSessionId()));
        return message;
    }

    /** The customer sees the agent's login username on agent messages and on joined / left events. */
    private static boolean namedAfterAgent(MessageSender sender, String text) {
        return sender == MessageSender.AGENT || (sender == MessageSender.SYSTEM && !UNNAMED_CODES.contains(text));
    }
}
