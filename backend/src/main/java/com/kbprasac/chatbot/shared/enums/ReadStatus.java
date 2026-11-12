package com.kbprasac.chatbot.shared.enums;

/** What came of reading the text in a file sent to the AI chat (docs/attachments-ai-chat.md). */
public enum ReadStatus {
    /** text found; the bot gets it after masking */
    READ,
    /** read, but no text in it (e.g. a photo without words) */
    NO_TEXT,
    /** OCR failed or took too long */
    FAILED,
    /** reading is switched off (OCR_ENABLED=false) */
    SKIPPED
}
