package com.kbprasac.chatbot.attachment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TesseractOcrTest {

    /** Shape of `tesseract img stdout tsv` (level 5 = word; conf -1 on layout rows). */
    private static final String TSV = String.join("\n",
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext",
            "1\t1\t0\t0\t0\t0\t0\t0\t800\t500\t-1\t",
            "4\t1\t1\t1\t1\t0\t10\t10\t500\t40\t-1\t",
            "5\t1\t1\t1\t1\t1\t10\t10\t90\t40\t96.1\tError",
            "5\t1\t1\t1\t1\t2\t110\t10\t60\t40\t95.3\t403:",
            "5\t1\t1\t1\t1\t3\t180\t10\t90\t40\t93.0\tlimit",
            "5\t1\t2\t1\t1\t1\t10\t300\t90\t40\t21.5\tmEnEowwss",
            "5\t1\t2\t1\t1\t2\t110\t300\t60\t40\t30.2\tZhems");

    @Test
    void linesAreGroupedWithTheirMeanConfidence() {
        var lines = TesseractOcr.lines(TSV);

        assertEquals(2, lines.size());
        assertEquals("Error 403: limit", lines.get(0).text());
        assertEquals("mEnEowwss Zhems", lines.get(1).text());
        assertEquals("Error 403: limit", OcrLines.confident(lines, 60));
    }
}
