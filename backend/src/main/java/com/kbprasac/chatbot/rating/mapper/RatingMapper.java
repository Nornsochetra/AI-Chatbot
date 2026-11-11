package com.kbprasac.chatbot.rating.mapper;

import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.rating.payload.response.RatingResponse;
import com.kbprasac.chatbot.shared.entity.Rating;

@Component
public class RatingMapper {

    public RatingResponse toResponse(Rating rating) {
        return new RatingResponse(rating.getId(), rating.getType(), rating.getScore(), rating.getComment(),
                rating.getCreatedAt());
    }
}
