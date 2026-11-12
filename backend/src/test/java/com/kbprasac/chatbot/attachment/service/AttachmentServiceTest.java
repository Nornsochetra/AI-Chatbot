package com.kbprasac.chatbot.attachment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.kbprasac.chatbot.audit.service.AuditService;
import com.kbprasac.chatbot.config.AttachmentProperties;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.AttachmentData;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.ReadStatus;
import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.exception.TooManyRequestsException;
import com.kbprasac.chatbot.shared.repository.AttachmentDataRepository;
import com.kbprasac.chatbot.shared.repository.AttachmentRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttachmentServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.7 test".getBytes();
    private static final byte[] EXE = "MZ\u0090\u0000 fake exe".getBytes();

    @Mock
    private AttachmentRepository attachmentRepository;
    @Mock
    private AttachmentDataRepository dataRepository;
    @Mock
    private HandoverService handoverService;
    @Mock
    private MalwareScanPort scanner;
    @Mock
    private AuditService audit;
    @Mock
    private FileTextReader textReader;

    private AttachmentService service;
    private Handover active;

    @BeforeEach
    void setUp() {
        service = new AttachmentService(attachmentRepository, dataRepository, handoverService, scanner, audit,
                new AttachmentProperties(30, 5, new AttachmentProperties.VirusScan(false, "localhost", 3310,
                        Duration.ofSeconds(1)), null),
                textReader, new InputMaskingService());
        active = Handover.builder().id(UUID.randomUUID()).sessionId("s1").status(HandoverStatus.ACTIVE).build();
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(active));
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Attachment stored(UUID handoverId, boolean deleted) {
        Attachment a = Attachment.builder().id(UUID.randomUUID()).handoverId(handoverId).sessionId("s1")
                .fileName("card.jpg").contentType(FileTypeDetector.JPEG).sizeBytes(7).createdAt(Instant.now())
                .deletedAt(deleted ? Instant.now() : null).build();
        when(attachmentRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void uploadStoresTheFileAsUploadedAndPostsItInTheChat() {
        Attachment a = service.upload("s1", "C:\\Users\\me\\photo.jpg", JPEG);

        assertEquals("photo.jpg", a.getFileName(), "folders are stripped from the name");
        assertEquals(FileTypeDetector.JPEG, a.getContentType());
        assertEquals(JPEG.length, a.getSizeBytes());
        verify(dataRepository).save(any(AttachmentData.class));
        verify(handoverService).customerAttachment(active, a);
    }

    @Test
    void uploadGoesToTheAgentWhileWaitingTooButNeedsALiveChat() {
        Handover waiting = Handover.builder().id(UUID.randomUUID()).sessionId("s1").status(HandoverStatus.WAITING).build();
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(waiting));
        Attachment a = service.upload("s1", "a.jpg", JPEG);
        assertEquals(waiting.getId(), a.getHandoverId());
        assertNotNull(a.getSentAt());
        verify(handoverService).customerAttachment(waiting, a);

        when(handoverService.findOpen("s2")).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> service.upload("s2", "a.jpg", JPEG));
    }

    // ---- AI chat (docs/attachments-ai-chat.md) ----

    @Test
    void inTheAiChatTheFileIsADraftWithItsTextReadAndMasked() {
        when(handoverService.findOpen("s2")).thenReturn(Optional.empty());
        when(textReader.read(JPEG, FileTypeDetector.JPEG)).thenReturn(new FileTextReader.Result(ReadStatus.READ,
                "Error 403: limit exceeded\nCard 4111 1111 1111 1111\nsokha@example.com"));

        AttachmentService.Received received = service.receive("s2", "error.jpg", JPEG);

        Attachment a = received.attachment();
        assertTrue(received.draft());
        assertNull(a.getHandoverId(), "no agent yet");
        assertNull(a.getSentAt(), "not sent until the customer presses Send");
        assertEquals(ReadStatus.READ, a.getReadStatus());
        assertTrue(a.getReadText().contains("Error 403: limit exceeded"));
        assertFalse(a.getReadText().contains("4111"), "card number masked");
        assertFalse(a.getReadText().contains("sokha@example.com"), "email masked");
        assertTrue(a.isReadMasked());
        verify(dataRepository).save(any(AttachmentData.class));
        verify(handoverService, never()).customerAttachment(any(), any());
    }

    @Test
    void withOcrOffTheDraftHasNoText() {
        when(handoverService.findOpen("s2")).thenReturn(Optional.empty());
        when(textReader.read(any(), any())).thenReturn(new FileTextReader.Result(ReadStatus.SKIPPED, null));

        Attachment a = service.receive("s2", "promo.png", JPEG).attachment();

        assertEquals(ReadStatus.SKIPPED, a.getReadStatus());
        assertNull(a.getReadText());
        assertFalse(a.isReadMasked());
    }

    @Test
    void withALiveChatOpenTheFileGoesToTheAgent() {
        AttachmentService.Received received = service.receive("s1", "a.jpg", JPEG);

        assertFalse(received.draft());
        assertEquals(active.getId(), received.attachment().getHandoverId());
        assertNull(received.attachment().getReadStatus(), "agent chat files are never read");
        verify(textReader, never()).read(any(), any());
    }

    @Test
    void aiChatFilesAreLimitedPerChat() {
        when(handoverService.findOpen("s2")).thenReturn(Optional.empty());
        when(attachmentRepository.countBySessionIdAndReadStatusIsNotNull("s2")).thenReturn(5L);
        assertThrows(TooManyRequestsException.class, () -> service.receive("s2", "a.jpg", JPEG));

        when(attachmentRepository.countBySessionIdAndReadStatusIsNotNull("s2")).thenReturn(1L);
        when(attachmentRepository.existsBySessionIdAndReadStatusIsNotNullAndCreatedAtAfter(eq("s2"), any()))
                .thenReturn(true);
        assertThrows(TooManyRequestsException.class, () -> service.receive("s2", "a.jpg", JPEG));
        verify(textReader, never()).read(any(), any());
    }

    private Attachment draft(String sessionId) {
        Attachment a = Attachment.builder().id(UUID.randomUUID()).sessionId(sessionId).fileName("error.jpg")
                .contentType(FileTypeDetector.JPEG).sizeBytes(7).createdAt(Instant.now())
                .readStatus(ReadStatus.READ).readText("Error 403").build();
        when(attachmentRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void sendingADraftMarksItSentOnlyInItsOwnChat() {
        Attachment a = draft("s2");

        assertThrows(NotFoundException.class, () -> service.takeForMessage("other", a.getId()));
        Attachment taken = service.takeForMessage("s2", a.getId());

        assertNotNull(taken.getSentAt());
        Instant first = taken.getSentAt();
        assertEquals(first, service.takeForMessage("s2", a.getId()).getSentAt(), "a retry keeps the same file");
    }

    @Test
    void agentChatFilesCannotBeSentToTheBot() {
        Attachment agentFile = stored(active.getId(), false);
        assertThrows(NotFoundException.class, () -> service.takeForMessage("s1", agentFile.getId()));
    }

    @Test
    void aDraftSentAfterAnAgentTookOverGoesToTheAgent() {
        Attachment a = draft("s1");

        service.sendToAgent(active, a);
        service.sendToAgent(active, a);

        assertEquals(active.getId(), a.getHandoverId());
        verify(handoverService).customerAttachment(active, a);
    }

    @Test
    void theCustomerCanDiscardADraftButNotASentFile() {
        Attachment a = draft("s2");
        when(dataRepository.existsById(a.getId())).thenReturn(true);

        service.discardDraft("s2", a.getId());

        verify(dataRepository).deleteById(a.getId());
        assertTrue(a.isDeleted());
        assertNull(a.getReadText(), "the masked text goes with the file");
        assertEquals("CUSTOMER", a.getDeleteReason());

        Attachment sent = draft("s2");
        sent.setSentAt(Instant.now());
        assertThrows(ConflictException.class, () -> service.discardDraft("s2", sent.getId()));
        assertThrows(NotFoundException.class, () -> service.discardDraft("other", sent.getId()));
    }

    @Test
    void unsentDraftsAreErased() {
        Attachment a = draft("s2");
        when(attachmentRepository.findByDeletedAtIsNullAndSentAtIsNullAndCreatedAtBefore(any())).thenReturn(List.of(a));
        when(dataRepository.existsById(a.getId())).thenReturn(true);

        assertEquals(1, service.deleteUnsentDrafts());
        assertEquals("UNSENT", a.getDeleteReason());
        verify(dataRepository).deleteById(a.getId());
    }

    @Test
    void typeComesFromTheContentNotTheName() {
        assertThrows(BadRequestException.class, () -> service.upload("s1", "photo.jpg", EXE)); // renamed .exe
        assertEquals(FileTypeDetector.PDF, service.upload("s1", "scan.jpg", PDF).getContentType());
    }

    @Test
    void emptyAndOversizedFilesAreRejected() {
        assertThrows(BadRequestException.class, () -> service.upload("s1", "a.jpg", new byte[0]));
        byte[] big = new byte[AttachmentService.MAX_BYTES + 1];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        assertThrows(BadRequestException.class, () -> service.upload("s1", "a.jpg", big));
    }

    @Test
    void infectedFileIsRejectedAndAudited() {
        when(scanner.findThreat(any())).thenReturn("Eicar-Signature");

        assertThrows(BadRequestException.class, () -> service.upload("s1", "a.pdf", PDF));
        verify(audit).record(eq(AuditService.SYSTEM), eq("ATTACHMENT_REJECTED_VIRUS"), anyString(), eq("s1"), eq("Eicar-Signature"));
        verify(dataRepository, never()).save(any());
    }

    @Test
    void agentViewReturnsTheBytesAndIsAudited() {
        Attachment a = stored(active.getId(), false);
        when(dataRepository.findById(a.getId())).thenReturn(Optional.of(new AttachmentData(a.getId(), JPEG)));

        AttachmentService.StoredFile file = service.openForAgent(active.getId(), a.getId(), "sokha");

        assertNotNull(file.data());
        verify(audit).record("sokha", "ATTACHMENT_VIEWED", "ATTACHMENT", a.getId().toString(), "card.jpg");
    }

    @Test
    void agentCannotOpenFilesOfAnotherChatOrDeletedFiles() {
        Attachment other = stored(UUID.randomUUID(), false);
        assertThrows(NotFoundException.class, () -> service.openForAgent(active.getId(), other.getId(), "sokha"));
        Attachment deleted = stored(active.getId(), true);
        assertThrows(NotFoundException.class, () -> service.openForAgent(active.getId(), deleted.getId(), "sokha"));
        verify(audit, never()).record(anyString(), eq("ATTACHMENT_VIEWED"), anyString(), anyString(), anyString());
    }

    @Test
    void agentDeleteErasesTheBytesTellsBothSidesAndIsAudited() {
        Attachment a = stored(active.getId(), false);
        when(dataRepository.existsById(a.getId())).thenReturn(true);
        when(handoverService.get(active.getId())).thenReturn(active);

        service.deleteByAgent(active.getId(), a.getId(), "sokha");

        verify(dataRepository).deleteById(a.getId());
        assertTrue(a.isDeleted());
        assertEquals("AGENT", a.getDeleteReason());
        verify(handoverService).attachmentRemoved(active, a);
        verify(audit).record("sokha", "ATTACHMENT_DELETED", "ATTACHMENT", a.getId().toString(), "card.jpg");
    }

    @Test
    void retentionErasesOldFiles() {
        Attachment old = stored(active.getId(), false);
        when(attachmentRepository.findByDeletedAtIsNullAndCreatedAtBefore(any())).thenReturn(List.of(old));
        when(dataRepository.existsById(old.getId())).thenReturn(true);

        assertEquals(1, service.deleteExpired());
        verify(dataRepository).deleteById(old.getId());
        assertEquals("RETENTION", old.getDeleteReason());
    }

    @Test
    void fileNamesAreLabelsOnly() {
        assertEquals("a.png", AttachmentService.cleanFileName("../../etc/a.png", FileTypeDetector.PNG));
        assertEquals("file.pdf", AttachmentService.cleanFileName("  ", FileTypeDetector.PDF));
        assertEquals("scriptx.jpg", AttachmentService.cleanFileName("<script>x.jpg", FileTypeDetector.JPEG).replace("script>", "script"));
        String longName = "a".repeat(300) + ".jpg";
        String cleaned = AttachmentService.cleanFileName(longName, FileTypeDetector.JPEG);
        assertEquals(AttachmentService.MAX_FILE_NAME, cleaned.length());
        assertTrue(cleaned.endsWith(".jpg"));
    }
}
