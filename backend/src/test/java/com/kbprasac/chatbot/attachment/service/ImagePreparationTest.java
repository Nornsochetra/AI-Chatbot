package com.kbprasac.chatbot.attachment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImagePreparationTest {

    private static final int RED = Color.RED.getRGB() & 0xFFFFFF;

    /** 40 x 20, white, with a red top-left corner, to see where it ends up */
    private static BufferedImage marked() {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                image.setRGB(x, y, Color.WHITE.getRGB());
            }
        }
        image.setRGB(0, 0, Color.RED.getRGB());
        return image;
    }

    private static int rgb(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) & 0xFFFFFF;
    }

    static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** A JPEG with an EXIF block saying how a phone held the camera (big-endian TIFF, one IFD entry). */
    static byte[] jpegWithOrientation(BufferedImage image, int orientation) throws IOException {
        byte[] plain = jpeg(image);
        byte[] exif = {
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,                   // TIFF header, IFD0 at offset 8
                0, 1,                                          // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, 1 value
                0, 0, 0, 0};                                   // no next IFD
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                                // SOI
        out.write(0xFF);
        out.write(0xE1);                                       // APP1
        out.write((exif.length + 2) >> 8);
        out.write((exif.length + 2) & 0xFF);
        out.write(exif);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    @Test
    void readsTheOrientationFlagOfPhonePhotos() throws IOException {
        assertEquals(1, ImagePreparation.exifOrientation(jpeg(marked())), "no EXIF: upright");
        assertEquals(6, ImagePreparation.exifOrientation(jpegWithOrientation(marked(), 6)));
        assertEquals(8, ImagePreparation.exifOrientation(jpegWithOrientation(marked(), 8)));
        assertEquals(1, ImagePreparation.exifOrientation(new byte[] {(byte) 0x89, 'P', 'N', 'G'}), "not a JPEG");
        assertEquals(1, ImagePreparation.exifOrientation(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0}),
                "broken EXIF is ignored");
    }

    @Test
    void turnsTheImageUpright() {
        BufferedImage right = ImagePreparation.upright(marked(), 6);  // stored turned left: rotate right
        assertEquals(20, right.getWidth());
        assertEquals(40, right.getHeight());
        assertEquals(RED, rgb(right, 19, 0));

        BufferedImage left = ImagePreparation.upright(marked(), 8);   // stored turned right: rotate left
        assertEquals(RED, rgb(left, 0, 39));

        BufferedImage upsideDown = ImagePreparation.upright(marked(), 3);
        assertEquals(RED, rgb(upsideDown, 39, 19));

        assertEquals(RED, rgb(ImagePreparation.upright(marked(), 2), 39, 0), "mirrored");
        assertEquals(RED, rgb(ImagePreparation.upright(marked(), 5), 0, 0), "mirrored across the diagonal");
        assertEquals(RED, rgb(ImagePreparation.upright(marked(), 7), 19, 39), "mirrored across the other diagonal");
    }

    @Test
    void darkModeScreenshotsAreInverted() {
        BufferedImage dark = new BufferedImage(50, 20, BufferedImage.TYPE_INT_RGB); // black, with a light "letter"
        dark.setRGB(10, 10, Color.WHITE.getRGB());

        BufferedImage prepared = ImagePreparation.greyForOcr(dark);

        assertEquals(BufferedImage.TYPE_BYTE_GRAY, prepared.getType());
        assertEquals(255, prepared.getRaster().getSample(0, 0, 0), "background now white");
        assertEquals(0, prepared.getRaster().getSample(10, 10, 0), "text now black");
    }

    @Test
    void lightScreenshotsAreOnlyMadeGrey() {
        BufferedImage prepared = ImagePreparation.greyForOcr(marked());

        assertEquals(255, prepared.getRaster().getSample(5, 5, 0));
        assertTrue(prepared.getRaster().getSample(0, 0, 0) < 128, "red corner stays dark");
    }
}
