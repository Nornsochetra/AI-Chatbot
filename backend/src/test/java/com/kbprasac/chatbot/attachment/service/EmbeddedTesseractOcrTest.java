package com.kbprasac.chatbot.attachment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kbprasac.chatbot.config.AttachmentProperties;
import com.kbprasac.chatbot.shared.enums.ReadStatus;

import net.sourceforge.tess4j.TessAPI;

class EmbeddedTesseractOcrTest {

    private static EmbeddedTesseractOcr ocr(String tessdata, String languages) {
        return new EmbeddedTesseractOcr(new AttachmentProperties(30, 5, null, new AttachmentProperties.Ocr(
                true, "embedded", "tesseract", tessdata, languages, Duration.ofSeconds(20), 60, 3, 1500)));
    }

    @Test
    void missingLanguageDataIsReportedWithoutStartingTesseract(@TempDir Path empty) {
        EmbeddedTesseractOcr ocr = ocr(empty.toString(), "khm+eng");

        assertEquals(List.of("khm", "eng"), ocr.missingLanguages());
        OcrPort.OcrException e = assertThrows(OcrPort.OcrException.class,
                () -> ocr.read(new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY), OcrPort.Layout.SCREENSHOT,
                        Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("khm"), e.getMessage());
    }

    @Test
    void theBundledEngineLoadsOnWindows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Tess4J bundles Windows x64 only");
        // loads libtesseract / libleptonica from the jar: proves nothing has to be installed
        assertTrue(TessAPI.INSTANCE.TessVersion().startsWith("5."));
    }

    // ---- with the real engine: run once the language files are in backend/tessdata (skipped until then) ----

    private static void assumeLanguageData(String... languages) {
        for (String language : languages) {
            assumeTrue(Files.isRegularFile(Path.of(EmbeddedTesseractOcr.DEFAULT_TESSDATA, language + ".traineddata")),
                    language + ".traineddata not downloaded yet");
        }
    }

    /** Generated text only, never real customer files. */
    private static BufferedImage textImage(String text, Font font) {
        return textImage(text, font, Color.WHITE, Color.BLACK);
    }

    private static BufferedImage textImage(String text, Font font, Color background, Color ink) {
        BufferedImage image = new BufferedImage(1000, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, 1000, 120);
        g.setColor(ink);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.drawString(text, 20, 75);
        g.dispose();
        return image;
    }

    @Test
    void readsEnglish() {
        assumeLanguageData("eng");

        String text = ocr("", "eng").read(textImage("Error 403: Transaction limit exceeded",
                new Font(Font.SANS_SERIF, Font.PLAIN, 40)), OcrPort.Layout.SCREENSHOT, Duration.ofSeconds(20));

        assertTrue(text.contains("403") && text.toLowerCase().contains("limit"), text);
    }

    @Test
    void readsKhmer() {
        assumeLanguageData("khm", "eng");
        Font khmer = new Font("Khmer UI", Font.PLAIN, 40);
        assumeTrue(khmer.canDisplayUpTo("ការផ្ទេរប្រាក់") == -1, "no Khmer font on this machine");

        String text = ocr("", "khm+eng").read(textImage("ការផ្ទេរប្រាក់បរាជ័យ", khmer), OcrPort.Layout.SCREENSHOT,
                Duration.ofSeconds(20));

        System.out.println("Khmer OCR: " + text);
        assertTrue(text.contains("ប្រាក់"), text);
    }

    private static FileTextReader pipeline() {
        AttachmentProperties props = new AttachmentProperties(30, 5, null, new AttachmentProperties.Ocr(
                true, "embedded", "tesseract", "", "khm+eng", Duration.ofSeconds(20), 60, 3, 1500));
        return new FileTextReader(new EmbeddedTesseractOcr(props), props);
    }

    @Test
    void readsADarkModeScreenshot() throws Exception {
        assumeLanguageData("khm", "eng");
        BufferedImage dark = textImage("Error 403: Transaction limit exceeded", new Font(Font.SANS_SERIF, Font.PLAIN, 40),
                new Color(18, 18, 24), new Color(230, 230, 235));
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(dark, "png", png);

        FileTextReader.Result result = pipeline().read(png.toByteArray(), FileTypeDetector.PNG);

        assertEquals(ReadStatus.READ, result.status());
        assertTrue(result.text().contains("403"), result.text());
    }

    @Test
    void readsASidewaysPhonePhoto() throws Exception {
        assumeLanguageData("khm", "eng");
        BufferedImage upright = textImage("Error 403: Transaction limit exceeded", new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        // stored turned left, with the flag saying "rotate right", as a phone does
        BufferedImage stored = ImagePreparation.upright(upright, 8);

        FileTextReader.Result result = pipeline().read(ImagePreparationTest.jpegWithOrientation(stored, 6),
                FileTypeDetector.JPEG);

        assertEquals(ReadStatus.READ, result.status());
        assertTrue(result.text().contains("403"), result.text());
    }

    /** Shapes and lines like an icon, chart or photo: Tesseract "reads" noise there; none of it may come back. */
    @Test
    void picturesWithoutTextGiveNoText() {
        assumeLanguageData("khm", "eng");
        BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        Random random = new Random(42);
        g.setColor(new Color(240, 244, 250));
        g.fillRect(0, 0, 800, 500);
        for (int i = 0; i < 60; i++) {
            g.setColor(new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200)));
            int x = random.nextInt(760);
            int y = random.nextInt(460);
            switch (i % 3) {
                case 0 -> g.fillOval(x, y, 6 + random.nextInt(30), 6 + random.nextInt(30));
                case 1 -> g.drawLine(x, y, x + random.nextInt(120) - 60, y + random.nextInt(120) - 60);
                default -> g.fillRect(x, y, 4 + random.nextInt(20), 4 + random.nextInt(40));
            }
        }
        g.dispose();

        String text = ocr("", "khm+eng").read(image, OcrPort.Layout.SCREENSHOT, Duration.ofSeconds(20));

        System.out.println("Noise OCR: [" + text + "]");
        assertTrue(FileTextReader.clean(text, 1500).isEmpty(), "should be empty: " + text);
    }
}
