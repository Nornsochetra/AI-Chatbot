package com.kbprasac.chatbot.handover.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.kbprasac.chatbot.audit.service.AuditService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.enums.MessageSender;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.repository.HandoverMessageRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentMessageServiceTest {

    private static final UUID HANDOVER = UUID.randomUUID();

    @Mock
    private HandoverMessageRepository messages;
    @Mock
    private AuditService audit;

    private HandoverMessage stored(long id, UUID handoverId, MessageSender sender, Attachment attachment) {
        HandoverMessage m = HandoverMessage.builder().id(id).handoverId(handoverId).sender(sender)
                .text("my account is 001234567, OTP [REDACTED]").attachment(attachment).build();
        when(messages.findById(id)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void theAgentSeesTheCustomersMessageAndTheViewIsAudited() {
        stored(42L, HANDOVER, MessageSender.CUSTOMER, null);

        HandoverMessage m = new AgentMessageService(messages, audit).openForAgent(HANDOVER, 42L, "sokha");

        assertEquals("my account is 001234567, OTP [REDACTED]", m.getText(), "personal data visible, secrets not");
        verify(audit).record("sokha", "MESSAGE_VIEWED", "HANDOVER_MESSAGE", "42", "handover " + HANDOVER);
    }

    @Test
    void onlyCustomerTextMessagesOfThatChat() {
        AgentMessageService service = new AgentMessageService(messages, audit);
        stored(1L, UUID.randomUUID(), MessageSender.CUSTOMER, null);
        stored(2L, HANDOVER, MessageSender.AGENT, null);
        stored(3L, HANDOVER, MessageSender.CUSTOMER, Attachment.builder().id(UUID.randomUUID()).build());

        assertThrows(NotFoundException.class, () -> service.openForAgent(HANDOVER, 1L, "sokha"), "another chat");
        assertThrows(NotFoundException.class, () -> service.openForAgent(HANDOVER, 2L, "sokha"), "agent message");
        assertThrows(NotFoundException.class, () -> service.openForAgent(HANDOVER, 3L, "sokha"), "a file");
        assertThrows(NotFoundException.class, () -> service.openForAgent(HANDOVER, 99L, "sokha"), "deleted");
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
