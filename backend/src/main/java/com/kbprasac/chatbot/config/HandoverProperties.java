package com.kbprasac.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kbprasac.chatbot.shared.enums.AgentInbox;

/**
 * @param autoCloseEnabled  close a live chat automatically when the customer is inactive. Off by default:
 *                          only the agent closes the chat. The requirements ask for auto-close, so it can be switched on.
 * @param inactivityTimeout how long without a customer message before the automatic close (requirement: 2 minutes)
 * @param waitingReminder   while no agent has accepted, remind the customer this often (0 = off)
 * @param inbox             where agents work: Botpress Desk (default) or our console (kept, switched off)
 */
@ConfigurationProperties(prefix = "app.handover")
public record HandoverProperties(boolean autoCloseEnabled, Duration inactivityTimeout, Duration waitingReminder,
                                 AgentInbox inbox) {

    public HandoverProperties {
        if (inbox == null) {
            inbox = AgentInbox.BOTPRESS_DESK;
        }
    }

    public boolean deskMode() {
        return inbox == AgentInbox.BOTPRESS_DESK;
    }
}
