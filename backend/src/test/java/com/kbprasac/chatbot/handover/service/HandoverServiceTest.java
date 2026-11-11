package com.kbprasac.chatbot.handover.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import com.kbprasac.chatbot.config.HandoverProperties;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.MessageSender;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;
import com.kbprasac.chatbot.shared.repository.HandoverMessageRepository;
import com.kbprasac.chatbot.shared.repository.HandoverRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandoverServiceTest {

    @Mock
    private HandoverRepository handoverRepository;
    @Mock
    private HandoverMessageRepository messageRepository;
    @Mock
    private ChatEventRepository chatEventRepository;
    @Mock
    private HelpdeskPort helpdesk;
    @Mock
    private ApplicationEventPublisher events;

    private HandoverService service;

    @BeforeEach
    void setUp() {
        service = serviceWithAutoClose(true);
        when(handoverRepository.save(any(Handover.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(HandoverMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatEventRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private HandoverService serviceWithAutoClose(boolean enabled) {
        return serviceWith(enabled, Duration.ofSeconds(60));
    }

    private HandoverService serviceWith(boolean autoClose, Duration waitingReminder) {
        return new HandoverService(handoverRepository, messageRepository, chatEventRepository, helpdesk,
                new HandoverProperties(autoClose, Duration.ofMinutes(2), waitingReminder, null), events);
    }

    private Handover waitingSince(Duration ago, Instant lastReminder) {
        Handover h = handover(HandoverStatus.WAITING, Instant.now());
        h.setCreatedAt(Instant.now().minus(ago));
        h.setLastReminderAt(lastReminder);
        return h;
    }

    private Handover handover(HandoverStatus status, Instant lastActivity) {
        Handover h = Handover.builder().id(UUID.randomUUID()).sessionId("s1").channel(Channel.WEBSITE)
                .status(status).reason(HandoverReason.USER_REQUEST).createdAt(Instant.now())
                .lastCustomerActivityAt(lastActivity).build();
        when(handoverRepository.findById(h.getId())).thenReturn(Optional.of(h));
        return h;
    }

    private List<HandoverMessage> savedMessages() {
        ArgumentCaptor<HandoverMessage> captor = ArgumentCaptor.forClass(HandoverMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void startOpensWaitingHandoverAndTellsTheCustomer() {
        when(handoverRepository.findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(eq("s1"), any()))
                .thenReturn(Optional.empty());

        Handover h = service.start("s1", Channel.WEBSITE, HandoverReason.USER_REQUEST);

        assertEquals(HandoverStatus.WAITING, h.getStatus());
        HandoverMessage system = savedMessages().get(0);
        assertEquals(MessageSender.SYSTEM, system.getSender());
        assertEquals("WAITING", system.getText());
        verify(helpdesk).ticketOpened(eq(h), any());
    }

    @Test
    void startReturnsTheOpenHandoverInsteadOfOpeningASecond() {
        Handover open = handover(HandoverStatus.ACTIVE, Instant.now());
        when(handoverRepository.findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(eq("s1"), any()))
                .thenReturn(Optional.of(open));

        assertSame(open, service.start("s1", Channel.WEBSITE, HandoverReason.USER_REQUEST));
        verify(helpdesk, never()).ticketOpened(any(), any());
    }

    @Test
    void acceptMakesItActiveAndRestartsTheInactivityTimer() {
        Handover h = handover(HandoverStatus.WAITING, Instant.now().minus(Duration.ofMinutes(10)));

        service.accept(h.getId(), "agent");

        assertEquals(HandoverStatus.ACTIVE, h.getStatus());
        assertEquals("agent", h.getAgentName());
        assertEquals(h.getAcceptedAt(), h.getLastCustomerActivityAt());
        assertEquals("AGENT_JOINED", savedMessages().get(0).getText());
    }

    @Test
    void agentMessagesAndJoinedEventCarryTheAgentLoginName() {
        Handover h = handover(HandoverStatus.WAITING, Instant.now());

        service.accept(h.getId(), "sokha");
        service.agentMessage(h.getId(), "Hello!");

        List<HandoverMessage> saved = savedMessages();
        assertEquals("AGENT_JOINED", saved.get(0).getText());
        assertEquals("sokha", saved.get(0).getSenderName());
        assertEquals(MessageSender.AGENT, saved.get(1).getSender());
        assertEquals("sokha", saved.get(1).getSenderName());
    }

    @Test
    void customerAndWaitingMessagesHaveNoName() {
        when(handoverRepository.findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(eq("s1"), any()))
                .thenReturn(Optional.empty());
        Handover h = service.start("s1", Channel.WEBSITE, HandoverReason.USER_REQUEST);
        service.customerMessage(h, "hi");

        savedMessages().forEach(m -> assertEquals(null, m.getSenderName()));
    }

    @Test
    void everyChangeIsAnnouncedForTheLivePush() {
        Handover h = handover(HandoverStatus.WAITING, Instant.now());

        service.accept(h.getId(), "agent");
        service.agentMessage(h.getId(), "Hello");
        service.close(h.getId(), HandoverCloseReason.AGENT_CLOSED);

        verify(events, org.mockito.Mockito.times(3)).publishEvent(new HandoverChangedEvent(h.getId(), "s1"));
    }

    @Test
    void acceptTwiceIsRejected() {
        Handover h = handover(HandoverStatus.ACTIVE, Instant.now());
        assertThrows(ConflictException.class, () -> service.accept(h.getId(), "agent2"));
    }

    @Test
    void agentCannotReplyBeforeAccepting() {
        Handover h = handover(HandoverStatus.WAITING, Instant.now());
        assertThrows(ConflictException.class, () -> service.agentMessage(h.getId(), "hello"));
    }

    @Test
    void customerMessageRefreshesActivity() {
        Instant old = Instant.now().minus(Duration.ofMinutes(1));
        Handover h = handover(HandoverStatus.ACTIVE, old);

        service.customerMessage(h, "hi");

        assertEquals(true, h.getLastCustomerActivityAt().isAfter(old));
        assertEquals(MessageSender.CUSTOMER, savedMessages().get(0).getSender());
    }

    @Test
    void closeByAgentSendsAgentLeftAndIsIdempotent() {
        Handover h = handover(HandoverStatus.ACTIVE, Instant.now());

        service.close(h.getId(), HandoverCloseReason.AGENT_CLOSED);
        service.close(h.getId(), HandoverCloseReason.AGENT_CLOSED);

        assertEquals(HandoverStatus.CLOSED, h.getStatus());
        assertEquals(List.of("AGENT_LEFT"), savedMessages().stream().map(HandoverMessage::getText).toList());
        verify(helpdesk).ticketClosed(h);
    }

    @Test
    void inactivityCheckClosesStaleActiveHandovers() {
        Handover stale = handover(HandoverStatus.ACTIVE, Instant.now().minus(Duration.ofMinutes(3)));
        when(handoverRepository.findByStatusAndLastCustomerActivityAtBefore(eq(HandoverStatus.ACTIVE), any()))
                .thenReturn(List.of(stale));

        service.closeInactive();

        assertEquals(HandoverStatus.CLOSED, stale.getStatus());
        assertEquals(HandoverCloseReason.CUSTOMER_INACTIVE, stale.getCloseReason());
        assertEquals("INACTIVITY_CLOSED", savedMessages().get(0).getText());
    }

    @Test
    void autoCloseOffLeavesSilentChatsOpen() {
        Handover stale = handover(HandoverStatus.ACTIVE, Instant.now().minus(Duration.ofHours(1)));
        when(handoverRepository.findByStatusAndLastCustomerActivityAtBefore(any(), any())).thenReturn(List.of(stale));

        serviceWithAutoClose(false).closeInactive();

        assertEquals(HandoverStatus.ACTIVE, stale.getStatus());
        verify(handoverRepository, never()).findByStatusAndLastCustomerActivityAtBefore(any(), any());
        verify(helpdesk, never()).ticketClosed(any());
    }

    @Test
    void inactivityCutoffIsTwoMinutesAgo() {
        when(handoverRepository.findByStatusAndLastCustomerActivityAtBefore(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        service.closeInactive();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(handoverRepository).findByStatusAndLastCustomerActivityAtBefore(eq(HandoverStatus.ACTIVE), cutoff.capture());
        long secondsBack = Duration.between(cutoff.getValue(), before).toSeconds();
        assertEquals(true, secondsBack >= 119 && secondsBack <= 121, "cutoff " + secondsBack + "s back");
        verify(messageRepository, never()).findBySessionIdAndIdGreaterThanOrderByIdAsc(any(), anyLong());
    }

    @Test
    void waitingCustomerGetsAReminderAfterTheInterval() {
        Handover h = waitingSince(Duration.ofSeconds(61), null);
        when(handoverRepository.findByStatus(HandoverStatus.WAITING)).thenReturn(List.of(h));

        service.remindWaiting();

        HandoverMessage reminder = savedMessages().get(0);
        assertEquals(MessageSender.SYSTEM, reminder.getSender());
        assertEquals("WAITING_REMINDER", reminder.getText());
        assertEquals(null, reminder.getSenderName());
        assertEquals(true, h.getLastReminderAt() != null);
        verify(events).publishEvent(new HandoverChangedEvent(h.getId(), "s1"));
    }

    @Test
    void noReminderBeforeTheIntervalSinceTheLastOne() {
        Handover fresh = waitingSince(Duration.ofSeconds(30), null);
        Handover remindedRecently = waitingSince(Duration.ofMinutes(5), Instant.now().minus(Duration.ofSeconds(20)));
        when(handoverRepository.findByStatus(HandoverStatus.WAITING)).thenReturn(List.of(fresh, remindedRecently));

        service.remindWaiting();

        verify(messageRepository, never()).save(any());
    }

    @Test
    void remindersRepeatEveryInterval() {
        Handover h = waitingSince(Duration.ofMinutes(5), Instant.now().minus(Duration.ofSeconds(61)));
        when(handoverRepository.findByStatus(HandoverStatus.WAITING)).thenReturn(List.of(h));

        service.remindWaiting();

        assertEquals("WAITING_REMINDER", savedMessages().get(0).getText());
    }

    @Test
    void zeroIntervalTurnsRemindersOff() {
        serviceWith(true, Duration.ZERO).remindWaiting();

        verify(handoverRepository, never()).findByStatus(any());
    }

    @Test
    void queuePositionCountsOlderWaitingHandovers() {
        Handover h = handover(HandoverStatus.WAITING, Instant.now());
        when(handoverRepository.countByStatusAndCreatedAtBefore(HandoverStatus.WAITING, h.getCreatedAt())).thenReturn(2L);

        assertEquals(3, service.queuePosition(h));
    }

    @Test
    void acceptingOrClosingAWaitingChatMovesTheQueue() {
        service.accept(handover(HandoverStatus.WAITING, Instant.now()).getId(), "agent");
        service.close(handover(HandoverStatus.WAITING, Instant.now()).getId(), HandoverCloseReason.AGENT_CLOSED);

        verify(events, org.mockito.Mockito.times(2)).publishEvent(new QueueChangedEvent());
    }

    @Test
    void closingAnActiveChatDoesNotMoveTheQueue() {
        service.close(handover(HandoverStatus.ACTIVE, Instant.now()).getId(), HandoverCloseReason.AGENT_CLOSED);

        verify(events, never()).publishEvent(new QueueChangedEvent());
    }

    @Test
    void deskAgentTakeoverOpensAnActiveHandoverWithoutATicket() {
        when(handoverRepository.findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(eq("s1"), any()))
                .thenReturn(Optional.empty());

        Handover h = service.startByAgent("s1", Channel.WEBSITE, "Sokha");

        assertEquals(HandoverStatus.ACTIVE, h.getStatus());
        assertEquals(HandoverReason.AGENT_INITIATED, h.getReason());
        assertEquals("Sokha", h.getAgentName());
        HandoverMessage joined = savedMessages().get(0);
        assertEquals("AGENT_JOINED", joined.getText());
        assertEquals("Sokha", joined.getSenderName());
        verify(helpdesk, never()).ticketOpened(any(), any());
    }

    @Test
    void recordedCustomerMessageIsNotForwardedAgain() {
        Handover h = handover(HandoverStatus.ACTIVE, Instant.now());

        service.recordCustomerMessage(h, "hello");

        assertEquals("hello", savedMessages().get(0).getText());
        verify(helpdesk, never()).customerMessage(any(), any());
    }

    @Test
    void customerUploadIsReportedToTheHelpdesk() {
        Handover h = handover(HandoverStatus.ACTIVE, Instant.now());
        com.kbprasac.chatbot.shared.entity.Attachment file = com.kbprasac.chatbot.shared.entity.Attachment.builder()
                .id(UUID.randomUUID()).handoverId(h.getId()).fileName("id.jpg").build();

        service.customerAttachment(h, file);

        verify(helpdesk).customerAttachment(h, file);
    }

    @Test
    void noQueuePositionOnceAnAgentHasIt() {
        assertEquals(null, service.queuePosition(handover(HandoverStatus.ACTIVE, Instant.now())));
    }
}
