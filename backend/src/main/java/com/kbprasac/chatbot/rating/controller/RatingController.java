package com.kbprasac.chatbot.rating.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kbprasac.chatbot.rating.mapper.RatingMapper;
import com.kbprasac.chatbot.rating.payload.request.RatingRequest;
import com.kbprasac.chatbot.rating.payload.response.RatingResponse;
import com.kbprasac.chatbot.rating.service.RatingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Customer side (widget). Public like the chat endpoint: the random session id identifies the chat. */
@RestController
@RequestMapping("/api/v1/chat/sessions/{sessionId}/rating")
@Tag(name = "Rating")
public class RatingController {

    private final RatingService ratingService;
    private final RatingMapper mapper;

    public RatingController(RatingService ratingService, RatingMapper mapper) {
        this.ratingService = ratingService;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "Rate the finished agent chat (AGENT) or the bot (AI); 409 if already rated or nothing to rate")
    public RatingResponse rate(@PathVariable String sessionId, @Valid @RequestBody RatingRequest request) {
        return mapper.toResponse(ratingService.submit(sessionId, request.type(), request.score(), request.comment()));
    }
}
