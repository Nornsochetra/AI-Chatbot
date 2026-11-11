package com.kbprasac.chatbot.handover.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

/** Event shapes copied from the Botpress live stream recorded on 2026-11-11 (names changed). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BotpressDeskEventsTest {

    private static final String AGENT_ASSIGNED = """
            {"type":"message_created","data":{"id":"m1","userId":"user_bot","payload":{"type":"text",
            "text":"Sokha has joined the conversation."},"metadata":{"hitl":{"kind":"agent_assigned",
            "ticketId":"tkt_1","agentName":"Sokha Chan"}}}}""";
    private static final String AGENT_REPLY = """
            {"type":"message_created","data":{"id":"m2","userId":"user_bot","payload":{"type":"text",
            "text":"Hello from Desk"},"metadata":{"adminName":"Sokha Chan"}}}""";
    private static final String TICKET_CLOSED = """
            {"type":"message_created","data":{"id":"m3","userId":"user_bot","payload":{"type":"text",
            "text":"Sokha closed the conversation. I will continue assisting you."},
            "metadata":{"hitl":{"kind":"ticket_closed","ticketId":"tkt_1","agentName":"Sokha Chan"}}}}""";
    private static final String CSAT_REQUEST = """
            {"type":"message_created","data":{"id":"m4","userId":"user_bot","payload":{"type":"choice",
            "text":"How would you rate your support experience?","options":[]},
            "metadata":{"hitl":{"kind":"csat_request","requestId":"csatrq_1"}}}}""";
    private static final String BOT_ANSWER = """
            {"type":"message_created","data":{"id":"m5","userId":"user_bot","payload":{"type":"text",
            "text":"Branches open at 8:00."},"metadata":{"streamId":"s:llmz_1:item-0","citations":[]}}}""";
    private static final String TYPING = """
            {"type":"typing_started","data":{}}""";

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private HandoverService handoverService;
    @Mock
    private BotpressDeskHelpdesk helpdesk;

    private BotpressDeskEvents events;

    @BeforeEach
    void setUp() {
        events = new BotpressDeskEvents(handoverService, helpdesk);
        when(handoverService.findOpen("s1")).thenReturn(Optional.empty());
    }

    private void receive(String event) throws Exception {
        JsonNode node = json.readTree(event);
        events.onEvent("s1", Channel.WEBSITE, node);
    }

    private static Handover handover(HandoverStatus status) {
        return Handover.builder().id(UUID.randomUUID()).sessionId("s1").channel(Channel.WEBSITE).status(status)
                .reason(HandoverReason.USER_REQUEST).createdAt(Instant.now()).build();
    }

    @Test
    void agentJoiningAWaitingChatAcceptsItAndForwardsTheWaitingMessages() throws Exception {
        Handover waiting = handover(HandoverStatus.WAITING);
        Handover accepted = handover(HandoverStatus.ACTIVE);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(waiting));
        when(handoverService.accept(waiting.getId(), "Sokha")).thenReturn(accepted);
        when(handoverService.messagesForHandover(accepted.getId(), 0)).thenReturn(List.of());

        receive(AGENT_ASSIGNED);

        verify(handoverService).accept(waiting.getId(), "Sokha");
        verify(helpdesk).forwardWaitingMessages(accepted, List.of());
    }

    @Test
    void agentJoiningABotChatOpensAnActiveHandover() throws Exception {
        receive(AGENT_ASSIGNED);

        verify(handoverService).startByAgent("s1", Channel.WEBSITE, "Sokha");
    }

    @Test
    void agentReplyIsShownToTheCustomer() throws Exception {
        Handover active = handover(HandoverStatus.ACTIVE);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(active));

        receive(AGENT_REPLY);

        verify(handoverService).agentMessage(active.getId(), "Hello from Desk");
        verify(handoverService, never()).accept(any(), anyString());
    }

    @Test
    void agentReplyWithoutAJoinEventStillOpensTheHandover() throws Exception {
        Handover started = handover(HandoverStatus.ACTIVE);
        when(handoverService.startByAgent("s1", Channel.WEBSITE, "Sokha")).thenReturn(started);

        receive(AGENT_REPLY);

        verify(handoverService).agentMessage(started.getId(), "Hello from Desk");
    }

    @Test
    void ticketClosedInDeskClosesTheHandover() throws Exception {
        Handover active = handover(HandoverStatus.ACTIVE);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(active));

        receive(TICKET_CLOSED);

        verify(handoverService).close(active.getId(), HandoverCloseReason.AGENT_CLOSED);
    }

    @Test
    void botAnswersDeskRatingRequestsAndTypingAreIgnored() throws Exception {
        receive(BOT_ANSWER);
        receive(CSAT_REQUEST);
        receive(TYPING);

        verify(handoverService, never()).startByAgent(anyString(), any(), anyString());
        verify(handoverService, never()).agentMessage(any(), anyString());
        verify(handoverService, never()).close(any(), any());
        verify(handoverService, never()).messagesForHandover(any(), anyLong());
        verifyNoInteractions(helpdesk);
    }

    @Test
    void customerSeesTheAgentsFirstName() {
        assertEquals("Sokha", BotpressDeskEvents.firstName("Sokha Chan"));
        assertEquals("Agent", BotpressDeskEvents.firstName(null));
        assertEquals("Agent", BotpressDeskEvents.firstName("  "));
    }
}
