package com.kbprasac.chatbot.chat.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kbprasac.chatbot.attachment.service.AttachmentService;
import com.kbprasac.chatbot.chat.mapper.ChatMapper;
import com.kbprasac.chatbot.config.ChatProperties;
import com.kbprasac.chatbot.chat.payload.request.ChatMessageRequest;
import com.kbprasac.chatbot.chat.payload.response.ChatMessageResponse;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService;
import com.kbprasac.chatbot.guardrail.service.InputMaskingService.MaskResult;
import com.kbprasac.chatbot.handover.service.HandoverService;
import com.kbprasac.chatbot.shared.entity.Attachment;
import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;
import com.kbprasac.chatbot.shared.enums.HandoverReason;
import com.kbprasac.chatbot.shared.enums.ReadStatus;
import com.kbprasac.chatbot.shared.enums.SessionState;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String FALLBACK_REPLY =
            "Sorry, I can't answer right now. Would you like to talk to a live agent? / "
                    + "សូមអភ័យទោស ខ្ញុំមិនអាចឆ្លើយបានទេ។ តើអ្នកចង់និយាយជាមួយភ្នាក់ងារផ្ទាល់ទេ?";

    /** The live chat ended on the customer side, but the agent has not closed it in Botpress Desk yet. */
    static final String AGENT_FINISHING_REPLY =
            "The agent is still closing your previous chat. The assistant will answer again in a moment. / "
                    + "ភ្នាក់ងារកំពុងបិទការជជែកមុនរបស់អ្នក។ ជំនួយការនឹងឆ្លើយតបម្តងទៀតក្នុងពេលបន្តិចទៀតនេះ។";

    /** A Desk agent sits in the chat without being connected (the customer did not ask), so Botpress pauses the bot. */
    static final String BOT_PAUSED_REPLY =
            "The assistant will answer again in a moment. / ជំនួយការនឹងឆ្លើយតបម្តងទៀតក្នុងពេលបន្តិចទៀតនេះ។";

    /** A file with no readable text and no question: nothing for the bot, so an agent is offered at once. */
    static final String FILE_UNREADABLE_REPLY =
            "I can't read this file, but a live agent can look at it. Would you like to talk to an agent? / "
                    + "ខ្ញុំមិនអាចអានឯកសារនេះបានទេ ប៉ុន្តែភ្នាក់ងារផ្ទាល់អាចពិនិត្យវាបាន។ តើអ្នកចង់និយាយជាមួយភ្នាក់ងារទេ?";

    private final BotConversations conversations;
    private final BotEnginePort botEngine;
    private final ChatSessionStore sessionStore;
    private final InputMaskingService masking;
    private final HandoverService handoverService;
    private final AttachmentService attachmentService;
    private final ChatEventRepository eventRepository;
    private final ChatMapper mapper;
    private final AutoRouteDetector autoRoute;
    private final ChatProperties props;

    public ChatService(BotEnginePort botEngine, BotConversations conversations, ChatSessionStore sessionStore,
                       InputMaskingService masking, HandoverService handoverService,
                       AttachmentService attachmentService, ChatEventRepository eventRepository, ChatMapper mapper,
                       AutoRouteDetector autoRoute, ChatProperties props) {
        this.botEngine = botEngine;
        this.conversations = conversations;
        this.sessionStore = sessionStore;
        this.masking = masking;
        this.handoverService = handoverService;
        this.attachmentService = attachmentService;
        this.eventRepository = eventRepository;
        this.mapper = mapper;
        this.autoRoute = autoRoute;
        this.props = props;
    }

    public ChatMessageResponse handle(ChatMessageRequest request) {
        ChatSession session = sessionStore.resolve(request.sessionId(), request.channel());
        String text = request.text() == null ? "" : request.text().strip();
        Attachment file = request.attachmentId() == null ? null
                : attachmentService.takeForMessage(session.sessionId(), request.attachmentId());

        // live agent waiting or active: the bot stays silent and the message goes to the agent.
        // Agents may collect personal data (requirement), so only secrets are masked here.
        Optional<Handover> handover = handoverService.findOpen(session.sessionId());
        if (handover.isPresent()) {
            if (file != null) {
                attachmentService.sendToAgent(handover.get(), file);
            }
            MaskResult masked = masking.maskSecrets(text);
            if (!text.isEmpty()) {
                handoverService.customerMessage(handover.get(), masked.text());
            }
            return mapper.toResponse(session.sessionId(), null, null, SessionState.HUMAN_ACTIVE, false,
                    masked.changed());
        }

        MaskResult masked = masking.mask(text);
        // with a file the bot gets the masked text read from it (never the file) plus the question
        String botText = file == null ? masked.text() : botTextFor(file, masked.text());
        boolean fileUnreadable = file != null && file.getReadStatus() != ReadStatus.READ;

        // BRD auto-route: an urgent need, or the customer asks for a person, goes to the live agent queue at once
        Optional<HandoverReason> route = props.autoRoute() ? autoRoute.detect(text) : Optional.empty();
        if (route.isPresent()) {
            return routeToAgent(session, route.get(), botText, masked.changed(), masking.maskSecrets(text).text(), true);
        }

        if (fileUnreadable && text.isEmpty()) {
            return reply(session, botText, FILE_UNREADABLE_REPLY, file, masked.changed(), true);
        }

        Optional<String> reply = askBot(session, botText);
        if (reply.isEmpty()) {
            // Botpress Desk: an agent took the chat over while we waited for the bot (the bot then stays silent).
            // The agent already saw the message in Desk; keep it in our record without forwarding it again.
            Optional<Handover> joined = handoverService.findOpen(session.sessionId());
            if (joined.isPresent()) {
                handoverService.recordCustomerMessage(joined.get(),
                        file != null ? botText : masking.maskSecrets(text).text());
                if (file != null) {
                    attachmentService.sendToAgent(joined.get(), file);
                }
                return mapper.toResponse(session.sessionId(), null, null, SessionState.HUMAN_ACTIVE, false,
                        masked.changed());
            }
        }
        boolean agentFinishing = reply.isEmpty() && handoverService.agentInboxStillOpen(session.sessionId());
        boolean botPaused = reply.isEmpty() && !agentFinishing && handoverService.botPausedByInbox(session.sessionId());
        if (reply.isEmpty() && !agentFinishing && !botPaused && props.autoRoute()
                && previousTurnFailed(session.sessionId())) {
            // BRD auto-route: repeated failed answers. The message is already in the Botpress conversation, so it is
            // recorded for the agent without being forwarded again.
            return routeToAgent(session, HandoverReason.AUTO_BOT_FAILED, botText, masked.changed(),
                    masking.maskSecrets(text).text(), false);
        }
        // an unreadable file always comes with the agent offer: only an agent can look at it
        boolean handoverSuggested = (reply.isEmpty() && !agentFinishing && !botPaused) || fileUnreadable;
        String replyText = reply.orElse(agentFinishing ? AGENT_FINISHING_REPLY
                : botPaused ? BOT_PAUSED_REPLY : FALLBACK_REPLY);
        return reply(session, botText, replyText, file, masked.changed(), handoverSuggested);
    }

    /**
     * Opens the live agent request for the customer. The customer sees the usual waiting message; the agent gets the
     * reason in the "[Live agent requested]" line, and the customer's message (secrets masked) when they join.
     *
     * @param forward the message did not reach the Botpress conversation, so Desk gets it with the waiting messages
     */
    private ChatMessageResponse routeToAgent(ChatSession session, HandoverReason reason, String botText,
                                             boolean inputMasked, String textForAgent, boolean forward) {
        Handover handover = handoverService.start(session.sessionId(), session.channel(), reason);
        if (!textForAgent.isEmpty()) {
            if (forward) {
                handoverService.customerMessage(handover, textForAgent);
            } else {
                handoverService.recordCustomerMessage(handover, textForAgent);
            }
        }
        eventRepository.save(mapper.toEvent(session.sessionId(), session.channel(), botText, null, inputMasked, false));
        return mapper.toRoutedResponse(session.sessionId(), inputMasked);
    }

    /** The bot's last answer in this chat was our fallback (it could not answer). */
    private boolean previousTurnFailed(String sessionId) {
        return eventRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
                .map(event -> FALLBACK_REPLY.equals(event.getBotReply()))
                .orElse(false);
    }

    private ChatMessageResponse reply(ChatSession session, String userText, String replyText, Attachment file,
                                      boolean inputMasked, boolean handoverSuggested) {
        ChatEvent event = mapper.toEvent(session.sessionId(), session.channel(), userText, replyText, inputMasked,
                handoverSuggested);
        event.setAttachmentId(file != null ? file.getId() : null);
        event = eventRepository.save(event);
        return mapper.toResponse(session.sessionId(), replyText, event.getId(), SessionState.BOT_ACTIVE,
                handoverSuggested, inputMasked);
    }

    /**
     * What the bot gets for a file: the masked text read from it, never the file. Botpress Studio is instructed
     * how to treat these bracketed blocks (docs/attachments-ai-chat.md).
     */
    static String botTextFor(Attachment file, String maskedQuestion) {
        String kind = file.isImage() ? "an image" : "a PDF";
        StringBuilder text = new StringBuilder();
        if (file.getReadStatus() == ReadStatus.READ && file.getReadText() != null) {
            text.append("[The customer sent ").append(kind).append(". Text found in it:]\n").append(file.getReadText());
        } else {
            text.append("[The customer sent ").append(kind).append("; no text could be read from it.]");
        }
        if (!maskedQuestion.isEmpty()) {
            text.append("\n[Customer's question:] ").append(maskedQuestion);
        }
        // Botpress Desk shows messages as Markdown, where a single line break is dropped and the parts run together
        // ("...from it.][Customer's question:]"); two spaces before it make a visible line break there
        return text.toString().replace("\n", "  \n");
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
