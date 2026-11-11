package com.kbprasac.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "botpress")
public record BotpressProperties(String baseUrl, long pollIntervalMs, long replyTimeoutMs) {
}
