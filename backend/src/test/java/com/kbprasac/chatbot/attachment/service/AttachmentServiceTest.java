package com.kbprasac.chatbot.attachment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.AttachmentData;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
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

    private AttachmentService service;
    private Handover active;

    @BeforeEach
    void setUp() {
        service = new AttachmentService(attachmentRepository, dataRepository, handoverService, scanner, audit,
                new AttachmentProperties(30, new AttachmentProperties.VirusScan(false, "localhost", 3310, Duration.ofSeconds(1))));
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
    void uploadOnlyWhileAnAgentIsChatting() {
        when(handoverService.findOpen("s1")).thenReturn(Optional.of(
                Handover.builder().id(UUID.randomUUID()).status(HandoverStatus.WAITING).build()));
        assertThrows(ConflictException.class, () -> service.upload("s1", "a.jpg", JPEG));

        when(handoverService.findOpen("s2")).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> service.upload("s2", "a.jpg", JPEG));
        verify(dataRepository, never()).save(any());
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
