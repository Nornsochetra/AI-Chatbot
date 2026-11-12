package com.kbprasac.chatbot.attachment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.kbprasac.chatbot.config.AttachmentProperties;
import com.kbprasac.chatbot.shared.enums.ReadStatus;

class FileTextReaderTest {

    /** Stands in for Tesseract: records the images it was given. */
    private static final class FakeOcr implements OcrPort {
        final List<BufferedImage> images = new ArrayList<>();
        final List<Layout> layouts = new ArrayList<>();
        final Function<BufferedImage, String> answer;

        FakeOcr(Function<BufferedImage, String> answer) {
            this.answer = answer;
        }

        @Override
        public String read(BufferedImage image, Layout layout, Duration timeout) {
            images.add(image);
            layouts.add(layout);
            return answer.apply(image);
        }
    }

    private static FileTextReader reader(OcrPort ocr, boolean enabled) {
        return new FileTextReader(ocr, new AttachmentProperties(30, 5, null,
                new AttachmentProperties.Ocr(enabled, "embedded", "tesseract", "", "khm+eng", Duration.ofSeconds(5),
                        60, 3, 1500)));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] pdf(String text, int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text != null) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(72, 700);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void offReadsNothing() throws IOException {
        FakeOcr ocr = new FakeOcr(image -> "text");

        FileTextReader.Result result = reader(ocr, false).read(png(10, 10), FileTypeDetector.PNG);

        assertEquals(ReadStatus.SKIPPED, result.status());
        assertNull(result.text());
        assertTrue(ocr.images.isEmpty());
    }

    @Test
    void largeImagesAreScaledDownBeforeOcr() throws IOException {
        FakeOcr ocr = new FakeOcr(image -> "  Error 403:   Transaction limit exceeded \n\n\n Try again  ");

        FileTextReader.Result result = reader(ocr, true).read(png(4000, 1000), FileTypeDetector.PNG);

        assertEquals(ReadStatus.READ, result.status());
        assertEquals("Error 403: Transaction limit exceeded\nTry again", result.text());
        assertEquals(FileTextReader.MAX_SIDE, ocr.images.get(0).getWidth());
        assertEquals(500, ocr.images.get(0).getHeight());
        assertEquals(OcrPort.Layout.SCREENSHOT, ocr.layouts.get(0));
    }

    @Test
    void smallScreenshotsAreEnlargedBeforeOcr() throws IOException {
        FakeOcr ocr = new FakeOcr(image -> "Error 403");

        reader(ocr, true).read(png(400, 300), FileTypeDetector.PNG);
        reader(ocr, true).read(png(800, 600), FileTypeDetector.PNG);

        assertEquals(1200, ocr.images.get(0).getWidth(), "at most 3x");
        assertEquals(FileTextReader.MAX_SIDE, ocr.images.get(1).getWidth(), "no larger than 2000 px");
    }

    @Test
    void phonePhotosAreTurnedUprightBeforeOcr() throws IOException {
        FakeOcr ocr = new FakeOcr(image -> "Error 403");
        byte[] sideways = ImagePreparationTest.jpegWithOrientation(new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB), 6);

        reader(ocr, true).read(sideways, FileTypeDetector.JPEG);

        BufferedImage read = ocr.images.get(0);
        assertTrue(read.getHeight() > read.getWidth(), "stored landscape, shown portrait");
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, read.getType());
    }

    @Test
    void symbolLinesAreDroppedButKhmerIsKept() {
        assertEquals("Error 403: Transaction limit exceeded\nការផ្ទេរប្រាក់បរាជ័យ",
                FileTextReader.clean("-- @'- - @»- - @ -g\nError 403: Transaction limit exceeded\n|| ~ ..\n"
                        + "ការផ្ទេរប្រាក់បរាជ័យ", 1500));
    }

    @Test
    void ocrNoiseCountsAsNoText() throws IOException {
        FileTextReader.Result result = reader(new FakeOcr(image -> " | ~ \n . "), true)
                .read(png(10, 10), FileTypeDetector.PNG);

        assertEquals(ReadStatus.NO_TEXT, result.status());
    }

    @Test
    void ocrFailureCountsAsFailed() throws IOException {
        FileTextReader.Result result = reader(new FakeOcr(image -> {
            throw new OcrPort.OcrException("Tesseract took longer than 5s");
        }), true).read(png(10, 10), FileTypeDetector.PNG);

        assertEquals(ReadStatus.FAILED, result.status());
        assertNull(result.text());
    }

    @Test
    void brokenFilesCountAsFailed() {
        byte[] notReallyAnImage = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};
        assertEquals(ReadStatus.FAILED,
                reader(new FakeOcr(image -> "x"), true).read(notReallyAnImage, FileTypeDetector.PNG).status());
    }

    @Test
    void pdfTextLayerIsReadWithoutOcr() throws IOException {
        FakeOcr ocr = new FakeOcr(image -> "should not be used");

        FileTextReader.Result result = reader(ocr, true).read(pdf("Promotion: 5% cashback", 1), FileTypeDetector.PDF);

        assertEquals(ReadStatus.READ, result.status());
        assertEquals("Promotion: 5% cashback", result.text());
        assertTrue(ocr.images.isEmpty());
    }

    @Test
    void scannedPdfPagesAreOcrdUpToTheLimit() throws IOException {
        FakeOcr ocr = new FakeOcr(image -> "Page text");

        FileTextReader.Result result = reader(ocr, true).read(pdf(null, 5), FileTypeDetector.PDF);

        assertEquals(ReadStatus.READ, result.status());
        assertEquals(3, ocr.images.size(), "max 3 pages");
        assertEquals(OcrPort.Layout.PAGE, ocr.layouts.get(0));
    }

    @Test
    void longTextIsCut() {
        String cleaned = FileTextReader.clean("word ".repeat(1000), 100);
        assertTrue(cleaned.length() <= 102);
        assertTrue(cleaned.endsWith("…"));
    }
}
