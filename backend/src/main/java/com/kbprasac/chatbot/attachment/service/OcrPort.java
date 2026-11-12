package com.kbprasac.chatbot.attachment.service;

import java.awt.image.BufferedImage;
import java.time.Duration;

/**
 * Swap point for OCR. Runs on our own server: the image never goes to a cloud service.
 * The default adapter runs Tesseract ({@link TesseractOcr}); PaddleOCR could replace it if Khmer reads poorly.
 */
public interface OcrPort {

    /** @return the text found (may be empty); throws {@link OcrException} on failure or timeout */
    String read(BufferedImage image, Layout layout, Duration timeout);

    /**
     * How the text is laid out, which decides Tesseract's page segmentation mode. Tested 2026-11-12 on a
     * dashboard screenshot: sparse mode read three times as many items as paragraph mode.
     */
    enum Layout {
        /** screenshots and photos: scattered labels, buttons, numbers (Tesseract psm 11, sparse text) */
        SCREENSHOT(11),
        /** a scanned document page: paragraphs (Tesseract psm 3, automatic) */
        PAGE(3);

        final int pageSegMode;

        Layout(int pageSegMode) {
            this.pageSegMode = pageSegMode;
        }
    }

    class OcrException extends RuntimeException {

        public OcrException(String message) {
            super(message);
        }

        public OcrException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
