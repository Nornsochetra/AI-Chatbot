package com.kbprasac.chatbot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Customer file uploads (docs/attachments-simple.md, docs/attachments-ai-chat.md).
 *
 * @param retentionDays   files are deleted automatically after this many days
 * @param maxFilesPerChat files a customer can send to the AI chat in one session (live agent chats have no limit)
 * @param virusScan       ClamAV (clamd); off until ClamAV is installed — the requirements require it before going live
 * @param ocr             reading the text in files sent to the AI chat; off until Tesseract is installed
 */
@ConfigurationProperties(prefix = "app.attachment")
public record AttachmentProperties(int retentionDays, int maxFilesPerChat, VirusScan virusScan, Ocr ocr) {

    public record VirusScan(boolean enabled, String host, int port, Duration timeout) {
    }

    /**
     * @param enabled       off: files can still be sent to the AI chat, but nothing is read (the bot offers an agent)
     * @param engine        embedded: Tesseract bundled as a Java library (nothing to install);
     *                      program: an installed tesseract program
     * @param tesseractPath program engine: the tesseract program (on PATH, or the full path to tesseract.exe)
     * @param tessdataDir   folder with khm.traineddata / eng.traineddata; empty = backend/tessdata (embedded) or
     *                      Tesseract's own (program)
     * @param languages     Tesseract languages, e.g. khm+eng
     * @param timeout       per file; slower counts as "could not be read"
     * @param minConfidence lines Tesseract is less sure about (0-100) are dropped: OCR noise from icons and photos
     * @param maxPdfPages   pages read from a PDF
     * @param maxChars      longest text passed to the bot
     */
    public record Ocr(boolean enabled, String engine, String tesseractPath, String tessdataDir, String languages,
                      Duration timeout, int minConfidence, int maxPdfPages, int maxChars) {
    }
}
