package com.kbprasac.chatbot.handover.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
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
import com.kbprasac.chatbot.config.HandoverProperties;
import com.kbprasac.chatbot.handover.service.BotpressDeskHelpdesk.NotConnected;
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
    private static final String OTHER_AGENT_ASSIGNED = """
            {"type":"message_created","data":{"id":"m6","userId":"user_bot","payload":{"type":"text",
            "text":"Dara has joined the conversation."},"metadata":{"hitl":{"kind":"agent_assigned",
            "ticketId":"tkt_1","agentName":"Dara  Keo"}}}}""";
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
        events = events(2, false);
        when(handoverService.findOpen("s1")).thenReturn(Optional.empty());
    }

    private BotpressDeskEvents events(int maxChatsPerAgent, boolean agentTakeover) {
        return new BotpressDeskEvents(handoverService, helpdesk, new HandoverProperties(false, Duration.ofMinutes(2),
                Duration.ofMinutes(1), Duration.ofSeconds(60), null, maxChatsPerAgent, agentTakeover));
    }

    private void receive(String event) throws Exception {
        JsonNode node = json.readTree(event);
        events.onEvent("s1", Channel.WEBSITE, node);
    }

    private static Handover handover(HandoverStatus status) {
        return Handover.builder().id(UUID.randomUUID()).sessionId("s1").channel(Channel.WEBSITE).status(status)
                .reason(HandoverReason.USER_REQUEST).createdAt(Instant.now()).build();
    }

    private Handover activeWith(String agentName, String agentKey) {
        Handover active = handover(HandoverStatus.ACTIVE);
        active.setAgentName(agentName);
        active.setAgentKey(agentKey);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(active));
        return active;
    }

    private void neverConnected() {
        verify(handoverService, never()).accept(any(), anyString(), anyString());
        verify(handoverService, never()).startByAgent(anyString(), any(), anyString(), anyString());
        verify(handoverService, never()).agentMessage(any(), anyString());
    }

    // ---- rule 1: only customers who asked ----

    @Test
    void anAgentJoiningAChatNobodyAskedForIsNotConnected() throws Exception {
        receive(AGENT_ASSIGNED);
        receive(AGENT_REPLY);

        neverConnected();
        verify(helpdesk, org.mockito.Mockito.times(2))
                .notConnected("s1", Channel.WEBSITE, "sokha chan", NotConnected.NO_REQUEST, null, 0);
    }

    @Test
    void withTakeoverOnAnAgentJoiningABotChatOpensAnActiveHandover() throws Exception {
        events = events(2, true);
        Handover started = handover(HandoverStatus.ACTIVE);
        when(handoverService.startByAgent("s1", Channel.WEBSITE, "Sokha", "sokha chan")).thenReturn(started);

        receive(AGENT_ASSIGNED);

        verify(handoverService).startByAgent("s1", Channel.WEBSITE, "Sokha", "sokha chan");
        verify(helpdesk).agentTookOver(started);
    }

    @Test
    void afterTheCustomerEndedTheChatDeskEventsDoNotPullThemBackIn() throws Exception {
        when(helpdesk.stillOpenAfterOurClose("s1")).thenReturn(true);

        receive(AGENT_ASSIGNED);
        receive(AGENT_REPLY);

        neverConnected();
        verify(helpdesk, never()).notConnected(anyString(), any(), anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void agentJoiningAWaitingChatAcceptsItAndForwardsTheWaitingMessages() throws Exception {
        Handover waiting = handover(HandoverStatus.WAITING);
        Handover accepted = handover(HandoverStatus.ACTIVE);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(waiting));
        when(handoverService.accept(waiting.getId(), "Sokha", "sokha chan")).thenReturn(accepted);

        receive(AGENT_ASSIGNED);

        verify(handoverService).accept(waiting.getId(), "Sokha", "sokha chan");
        // what the customer wrote while waiting is already in Desk: nothing is re-posted on join
        verify(helpdesk, never()).customerMessage(any(), any());
    }

    @Test
    void agentReplyWithoutAJoinEventStillAcceptsTheWaitingChat() throws Exception {
        Handover waiting = handover(HandoverStatus.WAITING);
        Handover accepted = handover(HandoverStatus.ACTIVE);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(waiting));
        when(handoverService.accept(waiting.getId(), "Sokha", "sokha chan")).thenReturn(accepted);

        receive(AGENT_REPLY);

        verify(handoverService).agentMessage(accepted.getId(), "Hello from Desk");
    }

    // ---- rule 2: one agent per chat ----

    @Test
    void theAgentWhoHasTheChatIsShownToTheCustomer() throws Exception {
        Handover active = activeWith("Sokha", "sokha chan");

        receive(AGENT_REPLY);

        verify(handoverService).agentMessage(active.getId(), "Hello from Desk");
        verify(handoverService, never()).accept(any(), anyString(), anyString());
    }

    @Test
    void aSecondAgentIsNotConnected() throws Exception {
        activeWith("Sokha", "sokha chan");

        receive(OTHER_AGENT_ASSIGNED);

        neverConnected();
        verify(helpdesk).notConnected("s1", Channel.WEBSITE, "dara keo", NotConnected.OTHER_AGENT, "Sokha", 0);
    }

    @Test
    void chatsAcceptedBeforeAgentKeysAreMatchedByFirstName() throws Exception {
        Handover active = activeWith("Sokha", null);

        receive(AGENT_REPLY);

        verify(handoverService).agentMessage(active.getId(), "Hello from Desk");
    }

    // ---- rule 3: at most N live chats per agent ----

    @Test
    void anAgentAtTheLimitIsNotConnectedAndCanReplyOnceFree() throws Exception {
        Handover waiting = handover(HandoverStatus.WAITING);
        Handover accepted = handover(HandoverStatus.ACTIVE);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(waiting));
        when(handoverService.activeChatsOf("sokha chan")).thenReturn(2L);

        receive(AGENT_ASSIGNED);

        neverConnected();
        verify(helpdesk).notConnected("s1", Channel.WEBSITE, "sokha chan", NotConnected.AGENT_AT_LIMIT, null, 2);

        // one of the agent's chats ended: the next reply connects them
        when(handoverService.activeChatsOf("sokha chan")).thenReturn(1L);
        when(handoverService.accept(waiting.getId(), "Sokha", "sokha chan")).thenReturn(accepted);
        receive(AGENT_REPLY);

        verify(handoverService).accept(waiting.getId(), "Sokha", "sokha chan");
        verify(handoverService).agentMessage(accepted.getId(), "Hello from Desk");
    }

    @Test
    void zeroMeansNoLimit() throws Exception {
        events = events(0, false);
        Handover waiting = handover(HandoverStatus.WAITING);
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(waiting));
        when(handoverService.activeChatsOf(anyString())).thenReturn(50L);
        when(handoverService.accept(eq(waiting.getId()), anyString(), anyString())).thenReturn(handover(HandoverStatus.ACTIVE));

        receive(AGENT_ASSIGNED);

        verify(handoverService).accept(waiting.getId(), "Sokha", "sokha chan");
    }

    // ---- closing and ignored events ----

    @Test
    void deskTicketCloseLetsTheBotAnswerAgain() throws Exception {
        when(helpdesk.stillOpenAfterOurClose("s1")).thenReturn(true);

        receive(TICKET_CLOSED);

        verify(helpdesk).deskTicketClosed("s1");
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

        neverConnected();
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

    @Test
    void agentsAreRecognisedByTheirFullName() {
        assertEquals("dara keo", BotpressDeskEvents.agentKey("  Dara   Keo "));
        assertEquals("agent", BotpressDeskEvents.agentKey(null));
    }
}
