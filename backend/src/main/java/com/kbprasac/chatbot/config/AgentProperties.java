package com.kbprasac.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** R&D: one agent account for the built-in agent console, from .env (AGENT_USERNAME / AGENT_PASSWORD). */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(String username, String password) {
}
