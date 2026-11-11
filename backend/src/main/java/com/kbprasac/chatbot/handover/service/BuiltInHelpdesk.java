package com.kbprasac.chatbot.handover.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.entity.Handover;

/** The R&D agent console (/agent) reads handovers straight from our database, so there is nothing to forward. */
@Component
public class BuiltInHelpdesk implements HelpdeskPort {

    private static final Logger log = LoggerFactory.getLogger(BuiltInHelpdesk.class);

    @Override
    public void ticketOpened(Handover handover, List<ChatEvent> botTranscript) {
        log.info("Handover {} opened for session {} ({} bot turns before it)", handover.getId(),
                handover.getSessionId(), botTranscript.size());
    }

    @Override
    public void customerMessage(Handover handover, String maskedText) {
        // the console polls the handover messages
    }

    @Override
    public void ticketClosed(Handover handover) {
        log.info("Handover {} closed ({})", handover.getId(), handover.getCloseReason());
    }
}
