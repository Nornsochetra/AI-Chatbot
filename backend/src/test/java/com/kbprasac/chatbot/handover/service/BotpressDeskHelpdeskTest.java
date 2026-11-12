package com.kbprasac.chatbot.handover.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.kbprasac.chatbot.chat.service.BotConversation;
import com.kbprasac.chatbot.chat.service.BotConversations;
import com.kbprasac.chatbot.chat.service.BotEnginePort;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverCloseReason;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.MessageSender;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BotpressDeskHelpdeskTest {

    private static final BotConversation CONVERSATION = new BotConversation("user_1", "key", "conv_1");

    @Mock
    private BotConversations conversations;
    @Mock
    private BotEnginePort botEngine;

    private BotpressDeskHelpdesk helpdesk;

    @BeforeEach
    void setUp() {
        helpdesk = new BotpressDeskHelpdesk(conversations, botEngine, new InputMaskingService(),
                new com.kbprasac.chatbot.chat.service.AutoRouteDetector(), "https://chat.example/");
        when(conversations.ensure(anyString(), any())).thenReturn(CONVERSATION);
    }

    private static Handover handover(HandoverStatus status) {
        return Handover.builder().id(UUID.randomUUID()).sessionId("s1").channel(Channel.WEBSITE).status(status)
                .reason(HandoverReason.USER_REQUEST).createdAt(Instant.now()).build();
    }

    private String posted() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(botEngine).send(eq(CONVERSATION), text.capture());
        return text.getValue();
    }

    @Test
    void customerAskingForAnAgentIsAnnouncedInTheConversation() {
        helpdesk.ticketOpened(handover(HandoverStatus.WAITING), List.of());

        assertTrue(posted().startsWith("[Live agent requested]"));
    }

    @Test
    void messagesWhileWaitingAreShownAtOnceSoTheAgentKnowsTheIssueBeforeJoining() {
        helpdesk.customerMessage(handover(HandoverStatus.WAITING), message(1L, "hi, I want to know about loans"));

        assertEquals("[Waiting for an agent] hi, I want to know about loans", posted());
    }

    @Test
    void urgentWaitingMessagesStandOutSoTheAgentTakesThemFirst() {
        // the example: one customer asks about information, the other lost money in a transfer
        helpdesk.customerMessage(handover(HandoverStatus.WAITING), message(1L, "I lost money during a transfer"));
        assertEquals("[Waiting for an agent · URGENT] I lost money during a transfer", posted());

        org.mockito.Mockito.clearInvocations(botEngine);
        helpdesk.customerMessage(handover(HandoverStatus.WAITING), message(2L, "hi, I want some information"));
        assertEquals("[Waiting for an agent] hi, I want some information", posted());
    }

    @Test
    void waitingMessagesWithPersonalDataKeepTheSecureLink() {
        Handover h = handover(HandoverStatus.WAITING);
        helpdesk.customerMessage(h, message(5L, "my card was stolen, account 001234567"));

        String text = posted();
        assertTrue(text.startsWith("[Waiting for an agent · URGENT] my card was stolen, account [REDACTED]"), text);
        assertTrue(text.contains("/agent/message.html?m=" + h.getId() + ".5"), text);
        assertFalse(text.contains("001234567"), text);
    }

    @Test
    void filesWhileWaitingAndFilesSentToTheAssistantAreShownAtOnce() {
        Handover h = handover(HandoverStatus.WAITING);
        Attachment receipt = Attachment.builder().id(UUID.randomUUID()).handoverId(h.getId()).sessionId("s1")
                .fileName("receipt.pdf").contentType("application/pdf").sizeBytes(4096).build();
        helpdesk.customerAttachment(h, receipt);
        assertEquals("[Waiting for an agent] " + helpdesk.fileNotice(receipt), posted());

        org.mockito.Mockito.clearInvocations(botEngine);
        Attachment earlier = Attachment.builder().id(UUID.randomUUID()).handoverId(h.getId()).sessionId("s1")
                .fileName("error.png").contentType("image/png").sizeBytes(2048).build();
        helpdesk.filesSentBefore(h, List.of(earlier));
        assertEquals("[Files the customer sent to the assistant before asking for an agent]\n- "
                + helpdesk.fileNotice(earlier), posted());

        org.mockito.Mockito.clearInvocations(botEngine);
        helpdesk.filesSentBefore(h, List.of());
        verify(botEngine, never()).send(any(), any());
    }

    private static HandoverMessage message(Long id, String text) {
        return HandoverMessage.builder().id(id).sender(MessageSender.CUSTOMER).text(text).build();
    }

    @Test
    void personalDataIsHiddenFromBotpressAndTheAgentGetsASecureLink() {
        // Botpress runs AI on the conversation, so it must not get the account number; the agent may see it
        Handover h = handover(HandoverStatus.ACTIVE);
        helpdesk.customerMessage(h, message(42L, "my account is 001234567, call me on 012 345 678"));

        String text = posted();
        assertFalse(text.contains("001234567"), text);
        assertFalse(text.contains("012 345 678"), text);
        assertTrue(text.contains("[REDACTED]"), text);
        assertTrue(text.contains("🔒 Personal details hidden from the AI. Open securely (agent sign-in required): "
                + "https://chat.example/agent/message.html?m=" + h.getId() + ".42"), text);
        assertFalse(text.contains("&"), "Desk turns & in links into &amp;");
    }

    @Test
    void messagesWithoutPersonalDataGetNoLink() {
        helpdesk.customerMessage(handover(HandoverStatus.ACTIVE), message(1L, "my card is blocked"));
        assertEquals("my card is blocked", posted());

        org.mockito.Mockito.clearInvocations(botEngine);
        // a secret was already hidden when stored, and stays hidden on the secure page too: nothing more to see
        helpdesk.customerMessage(handover(HandoverStatus.ACTIVE), message(2L, "my OTP is [REDACTED]"));
        assertFalse(posted().contains("Open securely"));
    }

    @Test
    void fileNoticeHasTypeSizeAndLinkButNoFileNameOrFile() {
        Handover h = handover(HandoverStatus.ACTIVE);
        Attachment file = Attachment.builder().id(UUID.randomUUID()).handoverId(h.getId())
                .fileName("ID_Sok_Dara.jpg").contentType("image/jpeg").sizeBytes(245_760).build();

        helpdesk.customerAttachment(h, file);

        String text = posted();
        assertTrue(text.contains("image, 240 KB"), text);
        assertTrue(text.contains("https://chat.example/agent/file.html?f=" + h.getId() + "." + file.getId()), text);
        assertFalse(text.contains("&"), "Desk turns & in links into &amp;: " + text);
        assertFalse(text.contains("Sok_Dara"));
    }

    @Test
    void autoRoutedRequestsSayWhyAndUrgentOnesStandOut() {
        Handover urgent = handover(HandoverStatus.WAITING);
        urgent.setReason(HandoverReason.AUTO_URGENT);
        helpdesk.ticketOpened(urgent, List.of());
        String text = posted();
        assertTrue(text.startsWith("[Live agent requested · URGENT]"), text);
        assertTrue(text.contains("routed automatically: the customer's message looks urgent"), text);

        org.mockito.Mockito.clearInvocations(botEngine);
        Handover failed = handover(HandoverStatus.WAITING);
        failed.setReason(HandoverReason.AUTO_BOT_FAILED);
        helpdesk.ticketOpened(failed, List.of());
        assertTrue(posted().contains("the bot could not answer twice in a row"));
    }

    @Test
    void theAgentIsToldWhichChannelTheCustomerUses() {
        Handover fromWebsite = handover(HandoverStatus.WAITING);
        helpdesk.ticketOpened(fromWebsite, List.of());
        assertTrue(posted().contains("talk to a live agent from the Website"));

        org.mockito.Mockito.clearInvocations(botEngine);
        Handover fromApp = handover(HandoverStatus.WAITING);
        fromApp.setChannel(Channel.MOBILE_APP);
        helpdesk.ticketOpened(fromApp, List.of());
        assertTrue(posted().contains("talk to a live agent from the Mobile Banking App"));
    }

    @Test
    void anAgentJoiningOnTheirOwnIsToldTheChannelToo() {
        Handover h = handover(HandoverStatus.ACTIVE);
        h.setChannel(Channel.MOBILE_APP);

        helpdesk.agentTookOver(h);

        assertEquals("[You joined this chat] The customer is chatting from the Mobile Banking App.", posted());
    }

    @Test
    void anAgentNobodyAskedForIsToldOnceAndTheBotCountsAsPaused() {
        helpdesk.notConnected("s1", Channel.WEBSITE, "sokha chan", BotpressDeskHelpdesk.NotConnected.NO_REQUEST, null, 0);
        helpdesk.notConnected("s1", Channel.WEBSITE, "sokha chan", BotpressDeskHelpdesk.NotConnected.NO_REQUEST, null, 0);

        assertTrue(posted().startsWith("[Not connected] This customer has not asked for a live agent"), "posted once");
        assertTrue(helpdesk.botPausedByInbox("s1"), "Desk pauses the bot while the agent is in the chat");

        helpdesk.deskTicketClosed("s1");
        assertFalse(helpdesk.botPausedByInbox("s1"), "the agent closed the ticket: the bot answers again");
    }

    @Test
    void aSecondAgentAndAnAgentAtTheLimitAreToldWhy() {
        helpdesk.notConnected("s1", Channel.WEBSITE, "dara keo", BotpressDeskHelpdesk.NotConnected.OTHER_AGENT, "Sokha", 0);
        assertEquals("[Not connected] Sokha is already helping this customer. Your messages are not shown to them.",
                posted());
        assertFalse(helpdesk.botPausedByInbox("s1"), "the chat is with Sokha, not the bot");

        org.mockito.Mockito.clearInvocations(botEngine);
        helpdesk.notConnected("s2", Channel.WEBSITE, "dara keo", BotpressDeskHelpdesk.NotConnected.AGENT_AT_LIMIT, null, 2);
        assertTrue(posted().startsWith("[Not connected] You already have 2 live chats, the most allowed at once."));
    }

    @Test
    void deskCloseIsNotEchoedBackButOurAutoCloseIsReported() {
        Handover closedInDesk = handover(HandoverStatus.CLOSED);
        closedInDesk.setCloseReason(HandoverCloseReason.AGENT_CLOSED);
        helpdesk.ticketClosed(closedInDesk);
        verify(botEngine, never()).send(any(), any());

        Handover inactive = handover(HandoverStatus.CLOSED);
        inactive.setCloseReason(HandoverCloseReason.CUSTOMER_INACTIVE);
        helpdesk.ticketClosed(inactive);
        assertTrue(posted().startsWith("[Chat ended]"));
    }

    @Test
    void customerEndingAnAgentChatAsksTheAgentToCloseAndHoldsTheBotUntilThen() {
        Handover h = handover(HandoverStatus.CLOSED);
        h.setAcceptedAt(Instant.now());
        h.setCloseReason(HandoverCloseReason.CUSTOMER_ENDED);

        helpdesk.ticketClosed(h);

        String text = posted();
        assertTrue(text.startsWith("[Customer ended the chat]"), text);
        assertTrue(text.contains("Please close this ticket"), text);
        assertTrue(helpdesk.stillOpenAfterOurClose("s1"));

        helpdesk.deskTicketClosed("s1");
        assertFalse(helpdesk.stillOpenAfterOurClose("s1"));
    }

    @Test
    void customerCancellingBeforeAnAgentJoinedDoesNotHoldTheBot() {
        Handover h = handover(HandoverStatus.CLOSED);
        h.setCloseReason(HandoverCloseReason.CUSTOMER_ENDED);

        helpdesk.ticketClosed(h);

        assertTrue(posted().startsWith("[Customer cancelled the request]"));
        assertFalse(helpdesk.stillOpenAfterOurClose("s1"));
    }

    @Test
    void botpressFailureDoesNotBreakTheHandover() {
        doThrow(new IllegalStateException("down")).when(botEngine).send(any(), any());

        helpdesk.customerMessage(handover(HandoverStatus.ACTIVE), message(1L, "hello"));
    }
}
