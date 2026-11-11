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
        helpdesk = new BotpressDeskHelpdesk(conversations, botEngine, new InputMaskingService(), "https://chat.example/");
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
    void messagesWhileWaitingAreHeldBackBecauseTheBotWouldAnswerThem() {
        helpdesk.customerMessage(handover(HandoverStatus.WAITING), "hello");

        verify(botEngine, never()).send(any(), any());
    }

    @Test
    void messagesToTheAgentAreFullyMasked() {
        // agents in our console see phone numbers; Botpress runs AI on the conversation, so it must not
        helpdesk.customerMessage(handover(HandoverStatus.ACTIVE), "call me on 012 345 678");

        assertFalse(posted().contains("012 345 678"));
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
    void joiningAgentGetsTheWaitingMessagesInOneBlock() {
        Handover h = handover(HandoverStatus.ACTIVE);
        List<HandoverMessage> messages = List.of(
                HandoverMessage.builder().sender(MessageSender.SYSTEM).text("WAITING").build(),
                HandoverMessage.builder().sender(MessageSender.CUSTOMER).text("my card is blocked").build(),
                HandoverMessage.builder().sender(MessageSender.CUSTOMER).text("call 012 345 678").build());

        helpdesk.forwardWaitingMessages(h, messages);

        String text = posted();
        assertEquals("[While waiting for an agent, the customer wrote]\n- my card is blocked\n- call "
                + new InputMaskingService().mask("012 345 678").text(), text);
    }

    @Test
    void nothingToForwardWhenTheCustomerWroteNothing() {
        helpdesk.forwardWaitingMessages(handover(HandoverStatus.ACTIVE),
                List.of(HandoverMessage.builder().sender(MessageSender.SYSTEM).text("WAITING").build()));

        verify(botEngine, never()).send(any(), any());
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
    void botpressFailureDoesNotBreakTheHandover() {
        doThrow(new IllegalStateException("down")).when(botEngine).send(any(), any());

        helpdesk.customerMessage(handover(HandoverStatus.ACTIVE), "hello");
    }
}
