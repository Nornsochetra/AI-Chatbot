package com.kbprasac.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kbprasac.chatbot.shared.enums.AgentInbox;

/**
 * @param autoCloseEnabled  close a live chat automatically when the customer is inactive (BRD: on). Off: only the
 *                          agent closes the chat
 * @param inactivityTimeout how long without a customer message before the automatic close (requirement: 2 minutes)
 * @param stillThereAfter   with auto-close on: ask "are you still connected?" after this long without a customer
 *                          message, before the close (BRD message customization). 0, or not shorter than the
 *                          inactivity timeout = off
 * @param waitingReminder   while no agent has accepted, remind the customer this often (0 = off)
 * @param inbox             where agents work: Botpress Desk (default) or our console (kept, switched off)
 * @param maxChatsPerAgent  live chats one agent may have at the same time (0 = no limit). Each chat has one agent.
 * @param agentTakeover     Botpress Desk: an agent who joins a chat the customer did not escalate takes it over.
 *                          Off: only customers who asked for an agent are connected
 */
@ConfigurationProperties(prefix = "app.handover")
public record HandoverProperties(boolean autoCloseEnabled, Duration inactivityTimeout, Duration stillThereAfter,
                                 Duration waitingReminder, AgentInbox inbox, int maxChatsPerAgent,
                                 boolean agentTakeover) {

    public HandoverProperties {
        if (inbox == null) {
            inbox = AgentInbox.BOTPRESS_DESK;
        }
        if (maxChatsPerAgent < 0) {
            maxChatsPerAgent = 0;
        }
    }

    /** The "are you still connected?" check runs: auto-close is on and the question comes before the close. */
    public boolean asksStillThere() {
        return autoCloseEnabled && stillThereAfter != null && stillThereAfter.isPositive()
                && inactivityTimeout != null && stillThereAfter.compareTo(inactivityTimeout) < 0;
    }

    public boolean deskMode() {
        return inbox == AgentInbox.BOTPRESS_DESK;
    }
}
