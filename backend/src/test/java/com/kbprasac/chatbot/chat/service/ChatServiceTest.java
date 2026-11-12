package com.kbprasac.chatbot.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

import com.kbprasac.chatbot.attachment.service.AttachmentService;
import com.kbprasac.chatbot.chat.mapper.ChatMapper;
import com.kbprasac.chatbot.chat.payload.request.ChatMessageRequest;
import com.kbprasac.chatbot.chat.payload.response.ChatMessageResponse;
import com.kbprasac.chatbot.config.ChatProperties;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.ReadStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;

/** Files sent to the AI chat (docs/attachments-ai-chat.md): the bot only gets the masked text. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final BotConversation CONVERSATION = new BotConversation("user_1", "key", "conv_1");

    @Mock
    private BotEnginePort botEngine;
    @Mock
    private BotConversations conversations;
    @Mock
    private ChatSessionStore sessionStore;
    @Mock
    private HandoverService handoverService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ChatEventRepository eventRepository;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(botEngine, conversations, sessionStore, new InputMaskingService(), handoverService,
                attachmentService, eventRepository, new ChatMapper(), new AutoRouteDetector(),
                new ChatProperties(java.time.Duration.ofMinutes(30), 2000, true, 90));
        when(sessionStore.resolve(any(), any())).thenReturn(new ChatSession("s1", Channel.WEBSITE, CONVERSATION));
        when(conversations.ensure(any(ChatSession.class))).thenReturn(CONVERSATION);
        when(handoverService.findOpen("s1")).thenReturn(Optional.empty());
        when(eventRepository.save(any(ChatEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Attachment file(ReadStatus status, String text) {
        Attachment file = Attachment.builder().id(UUID.randomUUID()).sessionId("s1").fileName("error.png")
                .contentType("image/png").sizeBytes(1000).createdAt(Instant.now()).sentAt(Instant.now())
                .readStatus(status).readText(text).build();
        when(attachmentService.takeForMessage("s1", file.getId())).thenReturn(file);
        return file;
    }

    private String sentToBot() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(botEngine).ask(eq(CONVERSATION), text.capture());
        return text.getValue();
    }

    private ChatEvent storedEvent() {
        ArgumentCaptor<ChatEvent> event = ArgumentCaptor.forClass(ChatEvent.class);
        verify(eventRepository).save(event.capture());
        return event.getValue();
    }

    @Test
    void theBotGetsTheMaskedTextFromTheFileWithTheQuestion() {
        Attachment file = file(ReadStatus.READ, "Error 403: Transaction limit exceeded");
        when(botEngine.ask(any(), anyString())).thenReturn(Optional.of("Your daily limit was reached."));

        ChatMessageResponse response = service.handle(
                new ChatMessageRequest(null, Channel.WEBSITE, "what does this mean? my OTP is 123456", file.getId()));

        String sent = sentToBot();
        // two spaces before each line break: Botpress Desk shows Markdown, where a plain line break is dropped
        assertEquals("[The customer sent an image. Text found in it:]  \nError 403: Transaction limit exceeded  \n"
                + "[Customer's question:] what does this mean? my OTP is " + InputMaskingService.MASK, sent);
        assertEquals("Your daily limit was reached.", response.reply());
        assertFalse(response.handoverSuggested());
        assertTrue(response.inputMasked());
        assertEquals(file.getId(), storedEvent().getAttachmentId());
    }

    @Test
    void anUnreadableFileWithoutAQuestionGetsTheAgentOfferWithoutAskingTheBot() {
        Attachment file = file(ReadStatus.SKIPPED, null);

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "", file.getId()));

        verify(botEngine, never()).ask(any(), anyString());
        assertEquals(ChatService.FILE_UNREADABLE_REPLY, response.reply());
        assertTrue(response.handoverSuggested());
        assertEquals("[The customer sent an image; no text could be read from it.]", storedEvent().getUserText());
    }

    @Test
    void anUnreadableFileWithAQuestionAsksTheBotAndStillOffersAnAgent() {
        Attachment file = file(ReadStatus.NO_TEXT, null);
        when(botEngine.ask(any(), anyString())).thenReturn(Optional.of("Promotions are listed on our website."));

        ChatMessageResponse response = service.handle(
                new ChatMessageRequest(null, Channel.WEBSITE, "is this promotion real?", file.getId()));

        assertEquals("[The customer sent an image; no text could be read from it.]  \n"
                + "[Customer's question:] is this promotion real?", sentToBot());
        assertTrue(response.handoverSuggested(), "only an agent can look at the file");
    }

    @Test
    void withALiveChatOpenTheFileGoesToTheAgentNotTheBot() {
        Attachment file = file(ReadStatus.READ, "Error 403");
        Handover active = Handover.builder().id(UUID.randomUUID()).sessionId("s1").status(HandoverStatus.ACTIVE).build();
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(active));

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "", file.getId()));

        verify(attachmentService).sendToAgent(active, file);
        verify(handoverService, never()).customerMessage(any(), any());
        verify(botEngine, never()).ask(any(), anyString());
        assertEquals(SessionState.HUMAN_ACTIVE, response.state());
        assertNull(response.reply());
    }

    @Test
    void whileDeskPausesTheBotTheCustomerIsToldItWillAnswerSoon() {
        when(botEngine.ask(any(), anyString())).thenReturn(Optional.empty());
        when(handoverService.botPausedByInbox("s1")).thenReturn(true);

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "opening hours?"));

        assertEquals(ChatService.BOT_PAUSED_REPLY, response.reply());
        assertFalse(response.handoverSuggested());
    }

    // ---- BRD auto-route ----

    private Handover started(HandoverReason reason) {
        Handover waiting = Handover.builder().id(UUID.randomUUID()).sessionId("s1").status(HandoverStatus.WAITING)
                .reason(reason).build();
        when(handoverService.start("s1", Channel.WEBSITE, reason)).thenReturn(waiting);
        return waiting;
    }

    @Test
    void anUrgentMessageGoesStraightToAnAgent() {
        Handover waiting = started(HandoverReason.AUTO_URGENT);

        ChatMessageResponse response = service.handle(
                new ChatMessageRequest(null, Channel.WEBSITE, "my card was stolen, my PIN is 4821"));

        verify(botEngine, never()).ask(any(), anyString());
        assertEquals(SessionState.HUMAN_ACTIVE, response.state());
        assertEquals(HandoverStatus.WAITING, response.handoverStatus(), "the widget switches to waiting");
        assertNull(response.reply());
        // the agent gets the message (secrets masked) when they join; it never went to the bot
        verify(handoverService).customerMessage(waiting, "my card was stolen, my PIN is " + InputMaskingService.MASK);
    }

    @Test
    void askingForAPersonInWordsOpensTheRequest() {
        started(HandoverReason.AUTO_ASKED_FOR_AGENT);

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "can I talk to a real person"));

        verify(handoverService).start("s1", Channel.WEBSITE, HandoverReason.AUTO_ASKED_FOR_AGENT);
        assertEquals(HandoverStatus.WAITING, response.handoverStatus());
    }

    @Test
    void theSecondFailedAnswerInARowGoesToAnAgent() {
        when(botEngine.ask(any(), anyString())).thenReturn(Optional.empty());
        when(eventRepository.findFirstBySessionIdOrderByCreatedAtDesc("s1")).thenReturn(Optional.of(
                ChatEvent.builder().botReply(ChatService.FALLBACK_REPLY).build()));
        Handover waiting = started(HandoverReason.AUTO_BOT_FAILED);

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "how about loans?"));

        assertEquals(HandoverStatus.WAITING, response.handoverStatus());
        // already in the Botpress conversation: recorded, not forwarded again
        verify(handoverService).recordCustomerMessage(waiting, "how about loans?");
        verify(handoverService, never()).customerMessage(any(), anyString());
    }

    @Test
    void theFirstFailedAnswerOnlyOffersAnAgent() {
        when(botEngine.ask(any(), anyString())).thenReturn(Optional.empty());
        when(eventRepository.findFirstBySessionIdOrderByCreatedAtDesc("s1")).thenReturn(Optional.of(
                ChatEvent.builder().botReply("Branches open at 8:00.").build()));

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "how about loans?"));

        assertEquals(ChatService.FALLBACK_REPLY, response.reply());
        assertTrue(response.handoverSuggested());
        assertNull(response.handoverStatus());
        verify(handoverService, never()).start(any(), any(), any());
    }

    @Test
    void autoRouteCanBeSwitchedOff() {
        service = new ChatService(botEngine, conversations, sessionStore, new InputMaskingService(), handoverService,
                attachmentService, eventRepository, new ChatMapper(), new AutoRouteDetector(),
                new ChatProperties(java.time.Duration.ofMinutes(30), 2000, false, 90));
        when(botEngine.ask(any(), anyString())).thenReturn(Optional.of("Please call our hotline to block your card."));

        ChatMessageResponse response = service.handle(new ChatMessageRequest(null, Channel.WEBSITE, "my card was stolen"));

        verify(handoverService, never()).start(any(), any(), any());
        assertEquals("Please call our hotline to block your card.", response.reply());
    }

    @Test
    void aPdfIsNamedAsSuch() {
        Attachment pdf = Attachment.builder().contentType("application/pdf").readStatus(ReadStatus.READ)
                .readText("Statement\nPage 1").build();
        assertEquals("[The customer sent a PDF. Text found in it:]  \nStatement  \nPage 1", ChatService.botTextFor(pdf, ""));
    }
}
