package com.kbprasac.chatbot.chat.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kbprasac.chatbot.chat.payload.request.ChatMessageRequest;
import com.kbprasac.chatbot.chat.payload.response.ChatConfigResponse;
import com.kbprasac.chatbot.chat.payload.response.ChatMessageResponse;
import com.kbprasac.chatbot.chat.service.ChatService;
import com.kbprasac.chatbot.config.HandoverProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat")
public class ChatController {

    private final ChatService chatService;
    private final HandoverProperties handoverProps;

    public ChatController(ChatService chatService, HandoverProperties handoverProps) {
        this.chatService = chatService;
        this.handoverProps = handoverProps;
    }

    @GetMapping("/config")
    @Operation(summary = "Widget settings that depend on the backend setup (agent inbox)")
    public ChatConfigResponse config() {
        return new ChatConfigResponse(handoverProps.inbox(), handoverProps.deskMode());
    }

    @PostMapping("/messages")
    @Operation(summary = "Send a customer message and get the bot reply")
    public ChatMessageResponse sendMessage(@Valid @RequestBody ChatMessageRequest request) {
        return chatService.handle(request);
    }
}
