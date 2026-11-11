package com.kbprasac.chatbot.shared.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kbprasac.chatbot.shared.entity.Rating;
import com.kbprasac.chatbot.shared.enums.RatingType;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    boolean existsByHandoverId(UUID handoverId);

    boolean existsBySessionIdAndType(String sessionId, RatingType type);

    Optional<Rating> findByHandoverId(UUID handoverId);
}
