package com.kbprasac.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Customer file uploads (docs/attachments-simple.md).
 *
 * @param retentionDays files are deleted automatically after this many days
 * @param virusScan     ClamAV (clamd); off until ClamAV is installed — the requirements require it before going live
 */
@ConfigurationProperties(prefix = "app.attachment")
public record AttachmentProperties(int retentionDays, VirusScan virusScan) {

    public record VirusScan(boolean enabled, String host, int port, Duration timeout) {
    }
}
