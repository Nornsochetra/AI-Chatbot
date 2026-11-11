package com.kbprasac.chatbot.handover.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.config.HandoverProperties;
import com.kbprasac.chatbot.handover.payload.response.AttachmentInfoResponse;
import com.kbprasac.chatbot.handover.payload.response.HandoverDetailResponse;
import com.kbprasac.chatbot.handover.payload.response.HandoverRatingResponse;
import com.kbprasac.chatbot.handover.payload.response.HandoverResponse;
import com.kbprasac.chatbot.handover.payload.response.HandoverSummaryResponse;
import com.kbprasac.chatbot.handover.payload.response.LiveMessageResponse;
import com.kbprasac.chatbot.handover.payload.response.SessionUpdatesResponse;
import com.kbprasac.chatbot.handover.payload.response.TranscriptEntryResponse;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.entity.HandoverMessage;
import com.kbprasac.chatbot.shared.entity.Rating;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;

@Component
public class HandoverMapper {

    private final HandoverProperties props;

    public HandoverMapper(HandoverProperties props) {
        this.props = props;
    }

    public HandoverResponse toResponse(Handover handover, Integer queuePosition) {
        return new HandoverResponse(handover.getSessionId(), SessionState.HUMAN_ACTIVE, handover.getStatus(),
                queuePosition);
    }

    public SessionUpdatesResponse toUpdates(Optional<Handover> open, Integer queuePosition,
                                            List<HandoverMessage> messages, long after) {
        long cursor = messages.isEmpty() ? after : messages.get(messages.size() - 1).getId();
        return new SessionUpdatesResponse(
                open.isPresent() ? SessionState.HUMAN_ACTIVE : SessionState.BOT_ACTIVE,
                open.map(Handover::getStatus).orElse(null),
                queuePosition,
                toMessages(messages),
                cursor);
    }

    public HandoverSummaryResponse toSummary(Handover h) {
        Instant autoCloseAt = props.autoCloseEnabled() && h.getStatus() == HandoverStatus.ACTIVE
                ? h.getLastCustomerActivityAt().plus(props.inactivityTimeout())
                : null;
        return new HandoverSummaryResponse(h.getId(), h.getSessionId(), h.getChannel(), h.getStatus(), h.getReason(),
                h.getAgentName(), h.getCreatedAt(), h.getAcceptedAt(), h.getLastCustomerActivityAt(), h.getClosedAt(),
                h.getCloseReason(), autoCloseAt);
    }

    public HandoverDetailResponse toDetail(Handover handover, List<ChatEvent> transcript, List<HandoverMessage> messages,
                                           Optional<Rating> rating) {
        List<TranscriptEntryResponse> turns = transcript.stream()
                .map(e -> new TranscriptEntryResponse(e.getUserText(), e.getBotReply(), e.getCreatedAt()))
                .toList();
        HandoverRatingResponse ratingResponse = rating
                .map(r -> new HandoverRatingResponse(r.getScore(), r.getComment(), r.getCreatedAt()))
                .orElse(null);
        return new HandoverDetailResponse(toSummary(handover), turns, toMessages(messages), ratingResponse);
    }

    public LiveMessageResponse toMessage(HandoverMessage m) {
        Attachment a = m.getAttachment();
        AttachmentInfoResponse attachment = a == null ? null
                : new AttachmentInfoResponse(a.getId(), a.getFileName(), a.getContentType(), a.getSizeBytes(), a.isDeleted());
        return new LiveMessageResponse(m.getId(), m.getSender(), m.getText(), m.getSenderName(), attachment,
                m.getCreatedAt());
    }

    public List<LiveMessageResponse> toMessages(List<HandoverMessage> messages) {
        return messages.stream().map(this::toMessage).toList();
    }
}
