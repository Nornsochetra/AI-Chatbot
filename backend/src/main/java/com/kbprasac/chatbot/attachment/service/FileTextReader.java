package com.kbprasac.chatbot.attachment.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.config.AttachmentProperties;
import com.kbprasac.chatbot.shared.enums.ReadStatus;

/**
 * Reads the text in a file sent to the AI chat, on this server (docs/attachments-ai-chat.md):
 * <ul>
 *   <li>image: small screenshots enlarged, large photos scaled down to at most {@value #MAX_SIDE} px, then OCR;
 *       lines Tesseract is unsure about and lines that are mostly symbols are dropped</li>
 *   <li>PDF: its text layer; a scanned PDF (no text layer) is rendered page by page and OCR'd</li>
 * </ul>
 * The result is the raw text: the caller masks it before it is stored or sent anywhere.
 */
@Component
public class FileTextReader {

    private static final Logger log = LoggerFactory.getLogger(FileTextReader.class);

    static final int MAX_SIDE = 2000;
    /** smaller images are enlarged: Tesseract reads small text badly */
    static final int MIN_SIDE = 1000;
    /** at most this much (tested on a 752 px dashboard screenshot: 3x read more than 2x) */
    private static final double MAX_ENLARGE = 3.0;
    private static final int PDF_DPI = 200;
    /** fewer letters or digits than this counts as "no text" (OCR noise on a photo) */
    private static final int MIN_CHARS = 4;

    public record Result(ReadStatus status, String text) {

        static Result of(ReadStatus status) {
            return new Result(status, null);
        }
    }

    private final OcrPort ocr;
    private final AttachmentProperties.Ocr config;

    public FileTextReader(OcrPort ocr, AttachmentProperties props) {
        this.ocr = ocr;
        this.config = props.ocr();
    }

    public Result read(byte[] data, String contentType) {
        if (!config.enabled()) {
            return Result.of(ReadStatus.SKIPPED);
        }
        Instant deadline = Instant.now().plus(config.timeout());
        try {
            String text = FileTypeDetector.PDF.equals(contentType) ? readPdf(data, deadline) : readImage(data, deadline);
            String cleaned = clean(text, config.maxChars());
            return countLettersAndDigits(cleaned) < MIN_CHARS ? Result.of(ReadStatus.NO_TEXT)
                    : new Result(ReadStatus.READ, cleaned);
        } catch (IOException | RuntimeException e) {
            // OCR failure or timeout, broken image, encrypted PDF... Never log the text; the reason is enough
            log.warn("Could not read the text in a {} file: {}", contentType, e.getMessage());
            return Result.of(ReadStatus.FAILED);
        }
    }

    private String readImage(byte[] data, Instant deadline) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("not a readable image");
        }
        // phone photos stored sideways are turned upright; dark-mode screenshots are inverted
        BufferedImage upright = ImagePreparation.upright(image, ImagePreparation.exifOrientation(data));
        return ocr.read(ImagePreparation.greyForOcr(resize(upright)), OcrPort.Layout.SCREENSHOT, remaining(deadline));
    }

    private String readPdf(byte[] data, Instant deadline) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(data)) {
            int pages = Math.min(pdf.getNumberOfPages(), config.maxPdfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String textLayer = stripper.getText(pdf);
            if (countLettersAndDigits(textLayer) >= MIN_CHARS) {
                return textLayer;
            }
            // a scan: no text layer, so OCR the pages
            PDFRenderer renderer = new PDFRenderer(pdf);
            StringBuilder text = new StringBuilder();
            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, PDF_DPI, ImageType.GRAY);
                text.append(ocr.read(resize(image), OcrPort.Layout.PAGE, remaining(deadline))).append('\n');
            }
            return text.toString();
        }
    }

    private static Duration remaining(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        if (left.isNegative() || left.isZero()) {
            throw new OcrPort.OcrException("time limit reached");
        }
        return left;
    }

    /** Small screenshots are enlarged (small text reads badly); large photos are scaled down (slow, no better). */
    static BufferedImage resize(BufferedImage image) {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        double scale;
        if (longSide > MAX_SIDE) {
            scale = (double) MAX_SIDE / longSide;
        } else if (longSide < MIN_SIDE) {
            scale = Math.min(MAX_ENLARGE, (double) MAX_SIDE / longSide);
        } else {
            return image;
        }
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /** One space between words, no empty lines, at most {@code maxChars}. */
    static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace("\f", "\n").lines()
                .map(line -> line.replaceAll("[\\p{Cntrl}&&[^\\t]]", "").replaceAll("\\s+", " ").strip())
                .filter(FileTextReader::looksLikeText)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars).strip() + " …" : cleaned;
    }

    /**
     * Mostly letters and digits, not symbols: "-- @'- - @»- -g" is OCR noise. Khmer vowel signs and subscripts are
     * combining marks and count as letters.
     */
    static boolean looksLikeText(String line) {
        long visible = line.codePoints().filter(c -> !Character.isWhitespace(c)).count();
        long letters = line.codePoints().filter(FileTextReader::isLetterLike).count();
        // 3+: sparse mode turns specks into 2-character scraps ("ខៈ", "១១")
        return letters >= 3 && letters * 2 >= visible;
    }

    private static boolean isLetterLike(int c) {
        int type = Character.getType(c);
        return Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    static int countLettersAndDigits(String text) {
        return text == null ? 0 : (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }
}
