package com.kbprasac.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
/**
 * @param autoRoute            BRD auto-route: urgent messages, asking for a person, or two failed bot answers in a row
 *                             open a live agent request without the button
 * @param historyRetentionDays chat history (bot turns and live chat messages) is deleted after this many days
 *                             (0 = kept)
 */
public record ChatProperties(Duration sessionTtl, int maxMessageLength, boolean autoRoute, int historyRetentionDays) {
}
