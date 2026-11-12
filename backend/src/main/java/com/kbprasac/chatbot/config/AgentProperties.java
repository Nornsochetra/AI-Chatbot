package com.kbprasac.chatbot.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * R&D: one agent account for the secure file and message pages, from .env (AGENT_USERNAME / AGENT_PASSWORD).
 *
 * @param allowedIps IPs or CIDR ranges agents may connect from (BRD internal access security); empty = anywhere
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(String username, String password, List<String> allowedIps) {

    public AgentProperties {
        allowedIps = allowedIps == null ? List.of()
                : allowedIps.stream().map(String::trim).filter(ip -> !ip.isEmpty()).toList();
    }
}
