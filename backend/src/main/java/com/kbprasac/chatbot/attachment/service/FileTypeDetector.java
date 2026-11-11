package com.kbprasac.chatbot.attachment.service;

import java.util.Optional;

/**
 * Detects the file type from the first bytes ("magic numbers"), not from the name or the browser's claim,
 * so a renamed .exe or .html cannot pass as an image.
 */
public final class FileTypeDetector {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String PDF = "application/pdf";

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private FileTypeDetector() {
    }

    /** The allowed type (JPEG, PNG, PDF), or empty for anything else. */
    public static Optional<String> detect(byte[] data) {
        if (startsWith(data, JPEG_MAGIC)) return Optional.of(JPEG);
        if (startsWith(data, PNG_MAGIC)) return Optional.of(PNG);
        if (startsWith(data, PDF_MAGIC)) return Optional.of(PDF);
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
