package com.kbprasac.chatbot.rating.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.Rating;
import com.kbprasac.chatbot.shared.enums.Channel;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.RatingType;
import com.kbprasac.chatbot.shared.exception.ConflictException;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;
import com.kbprasac.chatbot.shared.repository.HandoverRepository;
import com.kbprasac.chatbot.shared.repository.RatingRepository;

/**
 * Post-chat rating: AGENT rates the session's most recently finished agent chat (once per handover),
 * AI rates the bot (once per session). The widget decides when to ask; this service decides what is allowed.
 */
@Service
public class RatingService {

    private final RatingRepository ratingRepository;
    private final HandoverRepository handoverRepository;
    private final ChatEventRepository chatEventRepository;
    private final InputMaskingService masking;

    public RatingService(RatingRepository ratingRepository, HandoverRepository handoverRepository,
                         ChatEventRepository chatEventRepository, InputMaskingService masking) {
        this.ratingRepository = ratingRepository;
        this.handoverRepository = handoverRepository;
        this.chatEventRepository = chatEventRepository;
        this.masking = masking;
    }

    @Transactional
    public Rating submit(String sessionId, RatingType type, int score, String comment) {
        Rating.RatingBuilder rating = Rating.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .type(type)
                .score((short) score)
                .comment(maskComment(comment))
                .createdAt(Instant.now());

        if (type == RatingType.AGENT) {
            Handover handover = handoverRepository
                    .findFirstBySessionIdAndStatusOrderByClosedAtDesc(sessionId, HandoverStatus.CLOSED)
                    .orElseThrow(() -> new ConflictException("No finished agent chat to rate"));
            if (ratingRepository.existsByHandoverId(handover.getId())) {
                throw new ConflictException("This agent chat is already rated");
            }
            rating.handoverId(handover.getId()).channel(handover.getChannel());
        } else {
            Optional<Channel> channel = chatEventRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                    .findFirst().map(e -> e.getChannel());
            if (channel.isEmpty()) {
                throw new ConflictException("No AI chat to rate");
            }
            if (ratingRepository.existsBySessionIdAndType(sessionId, RatingType.AI)) {
                throw new ConflictException("The AI chat is already rated");
            }
            rating.channel(channel.get());
        }
        return ratingRepository.save(rating.build());
    }

    @Transactional(readOnly = true)
    public Optional<Rating> forHandover(UUID handoverId) {
        return ratingRepository.findByHandoverId(handoverId);
    }

    /** Staff read comments later, so they are masked like bot messages. Blank comments are not stored. */
    private String maskComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return masking.mask(comment.trim()).text();
    }
}
