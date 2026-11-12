package com.kbprasac.chatbot.attachment.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Keeps only the lines Tesseract is confident about. On icons, charts, logos or photos Tesseract still "reads"
 * something ("-- @'- - @»- -g", "mEnEowwss"), with low confidence; those lines must not reach the bot.
 */
final class OcrLines {

    /** one recognised line with Tesseract's confidence, 0-100 */
    record Line(String text, float confidence) {
    }

    private OcrLines() {
    }

    static String confident(List<Line> lines, int minConfidence) {
        return lines.stream()
                .filter(line -> line.confidence() >= minConfidence)
                .map(line -> line.text().strip())
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n"));
    }
}
