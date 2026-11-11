package com.kbprasac.chatbot.shared.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverStatus;

public interface HandoverRepository extends JpaRepository<Handover, UUID> {

    Optional<Handover> findFirstBySessionIdAndStatusInOrderByCreatedAtDesc(String sessionId,
                                                                          Collection<HandoverStatus> statuses);

    List<Handover> findByStatusInOrderByCreatedAtAsc(Collection<HandoverStatus> statuses);

    List<Handover> findByStatus(HandoverStatus status);

    /** how many are ahead in the queue */
    long countByStatusAndCreatedAtBefore(HandoverStatus status, Instant createdAt);

    List<Handover> findByStatusAndLastCustomerActivityAtBefore(HandoverStatus status, Instant cutoff);

    /** The most recently finished agent chat of a session (the one a customer rates). */
    Optional<Handover> findFirstBySessionIdAndStatusOrderByClosedAtDesc(String sessionId, HandoverStatus status);
}
