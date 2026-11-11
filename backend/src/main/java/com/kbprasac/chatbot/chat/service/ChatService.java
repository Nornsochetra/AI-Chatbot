package com.kbprasac.chatbot.chat.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kbprasac.chatbot.chat.mapper.ChatMapper;
import com.kbprasac.chatbot.chat.payload.request.ChatMessageRequest;
import com.kbprasac.chatbot.chat.payload.response.ChatMessageResponse;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService.MaskResult;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.SessionState;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String FALLBACK_REPLY =
            "Sorry, I can't answer right now. Would you like to talk to a live agent? / "
                    + "សូមអភ័យទោស ខ្ញុំមិនអាចឆ្លើយបានទេ។ តើអ្នកចង់និយាយជាមួយភ្នាក់ងារផ្ទាល់ទេ?";

    private final BotConversations conversations;
    private final BotEnginePort botEngine;
    private final ChatSessionStore sessionStore;
    private final InputMaskingService masking;
    private final HandoverService handoverService;
    private final ChatEventRepository eventRepository;
    private final ChatMapper mapper;

    public ChatService(BotEnginePort botEngine, BotConversations conversations, ChatSessionStore sessionStore,
                       InputMaskingService masking, HandoverService handoverService,
                       ChatEventRepository eventRepository, ChatMapper mapper) {
        this.botEngine = botEngine;
        this.conversations = conversations;
        this.sessionStore = sessionStore;
        this.masking = masking;
        this.handoverService = handoverService;
        this.eventRepository = eventRepository;
        this.mapper = mapper;
    }

    public ChatMessageResponse handle(ChatMessageRequest request) {
        ChatSession session = sessionStore.resolve(request.sessionId(), request.channel());

        // live agent waiting or active: the bot stays silent and the message goes to the agent.
        // Agents may collect personal data (requirement), so only secrets are masked here.
        Optional<Handover> handover = handoverService.findOpen(session.sessionId());
        if (handover.isPresent()) {
            MaskResult masked = masking.maskSecrets(request.text());
            handoverService.customerMessage(handover.get(), masked.text());
            return mapper.toResponse(session.sessionId(), null, null, SessionState.HUMAN_ACTIVE, false,
                    masked.changed());
        }

        MaskResult masked = masking.mask(request.text());
        Optional<String> reply = askBot(session, masked.text());
        if (reply.isEmpty()) {
            // Botpress Desk: an agent took the chat over while we waited for the bot (the bot then stays silent).
            // The agent already saw the message in Desk; keep it in our record without forwarding it again.
            Optional<Handover> joined = handoverService.findOpen(session.sessionId());
            if (joined.isPresent()) {
                handoverService.recordCustomerMessage(joined.get(), masking.maskSecrets(request.text()).text());
                return mapper.toResponse(session.sessionId(), null, null, SessionState.HUMAN_ACTIVE, false,
                        masked.changed());
            }
        }
        boolean handoverSuggested = reply.isEmpty();
        String replyText = reply.orElse(FALLBACK_REPLY);

        ChatEvent event = eventRepository.save(mapper.toEvent(session.sessionId(), session.channel(), masked.text(),
                replyText, masked.changed(), handoverSuggested));
        return mapper.toResponse(session.sessionId(), replyText, event.getId(), SessionState.BOT_ACTIVE,
                handoverSuggested, masked.changed());
    }

    private Optional<String> askBot(ChatSession session, String text) {
        try {
            return botEngine.ask(conversations.ensure(session), text);
        } catch (RuntimeException e) {
            log.warn("Bot engine call failed for session {}", session.sessionId(), e);
            return Optional.empty();
        }
    }
}
