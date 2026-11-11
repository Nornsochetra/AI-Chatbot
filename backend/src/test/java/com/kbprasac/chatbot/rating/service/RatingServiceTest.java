package com.kbprasac.chatbot.rating.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.Rating;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.RatingType;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;
import com.kbprasac.chatbot.shared.repository.HandoverRepository;
import com.kbprasac.chatbot.shared.repository.RatingRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RatingServiceTest {

    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private HandoverRepository handoverRepository;
    @Mock
    private ChatEventRepository chatEventRepository;

    private RatingService service;

    @BeforeEach
    void setUp() {
        service = new RatingService(ratingRepository, handoverRepository, chatEventRepository, new InputMaskingService());
        when(ratingRepository.save(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Handover closedHandover() {
        Handover h = Handover.builder().id(UUID.randomUUID()).sessionId("s1").channel(Channel.MOBILE_APP)
                .status(HandoverStatus.CLOSED).closedAt(Instant.now()).build();
        when(handoverRepository.findFirstBySessionIdAndStatusOrderByClosedAtDesc("s1", HandoverStatus.CLOSED))
                .thenReturn(Optional.of(h));
        return h;
    }

    private void botChatted() {
        when(chatEventRepository.findBySessionIdOrderByCreatedAtAsc("s1"))
                .thenReturn(List.of(ChatEvent.builder().sessionId("s1").channel(Channel.WEBSITE).build()));
    }

    @Test
    void agentRatingIsLinkedToTheLastFinishedHandover() {
        Handover h = closedHandover();

        Rating r = service.submit("s1", RatingType.AGENT, 5, "Very helpful");

        assertEquals(h.getId(), r.getHandoverId());
        assertEquals(Channel.MOBILE_APP, r.getChannel());
        assertEquals(5, r.getScore());
        assertEquals("Very helpful", r.getComment());
    }

    @Test
    void agentRatingWithoutAFinishedChatIsRejected() {
        when(handoverRepository.findFirstBySessionIdAndStatusOrderByClosedAtDesc(eq("s1"), any())).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> service.submit("s1", RatingType.AGENT, 4, null));
    }

    @Test
    void agentChatCanOnlyBeRatedOnce() {
        Handover h = closedHandover();
        when(ratingRepository.existsByHandoverId(h.getId())).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.submit("s1", RatingType.AGENT, 4, null));
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void aiRatingNeedsABotConversation() {
        when(chatEventRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of());
        assertThrows(ConflictException.class, () -> service.submit("s1", RatingType.AI, 3, null));
    }

    @Test
    void aiRatingOncePerSession() {
        botChatted();
        when(ratingRepository.existsBySessionIdAndType("s1", RatingType.AI)).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.submit("s1", RatingType.AI, 3, null));
    }

    @Test
    void aiRatingIsStoredWithoutHandover() {
        botChatted();

        Rating r = service.submit("s1", RatingType.AI, 2, "  ");

        assertNull(r.getHandoverId());
        assertEquals(Channel.WEBSITE, r.getChannel());
        assertNull(r.getComment(), "blank comment is not stored");
    }

    @Test
    void commentIsMaskedLikeBotMessages() {
        closedHandover();

        Rating r = service.submit("s1", RatingType.AGENT, 1, "agent asked my OTP 123456, call 012 345 678");

        assertEquals("agent asked my OTP [REDACTED], call [REDACTED]", r.getComment());
    }
}
