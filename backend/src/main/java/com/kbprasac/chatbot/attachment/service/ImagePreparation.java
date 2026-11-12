package com.kbprasac.chatbot.attachment.service;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Gets an image ready for OCR, which Tesseract does not do by itself:
 * <ul>
 *   <li>Phone photos: a JPEG is often stored sideways with an EXIF "orientation" flag that viewers apply but Java
 *       ignores; Tesseract would read a sideways image and find nothing.</li>
 *   <li>Dark mode: light text on a dark background reads badly, so dark images are turned grey and inverted.</li>
 * </ul>
 */
final class ImagePreparation {

    /** average brightness (0-255) below which an image counts as dark mode */
    static final int DARK_BELOW = 110;

    private ImagePreparation() {
    }

    // ---------------- EXIF orientation ----------------

    /** The EXIF orientation of a JPEG (1 = upright … 8), or 1 when there is none or it cannot be read. */
    static int exifOrientation(byte[] jpeg) {
        try {
            if (jpeg.length < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) {
                return 1;
            }
            int pos = 2;
            while (pos + 4 <= jpeg.length && u8(jpeg, pos) == 0xFF) {
                int marker = u8(jpeg, pos + 1);
                int length = (u8(jpeg, pos + 2) << 8) | u8(jpeg, pos + 3);
                if (marker == 0xDA || marker == 0xD9) {
                    return 1; // image data starts: no EXIF before it
                }
                if (marker == 0xE1 && length >= 8 && startsWith(jpeg, pos + 4, "Exif\0\0")) {
                    return orientationInTiff(jpeg, pos + 10, pos + 2 + length);
                }
                pos += 2 + length;
            }
        } catch (RuntimeException e) {
            // a broken EXIF block: read the image as it is
        }
        return 1;
    }

    private static int orientationInTiff(byte[] data, int tiff, int end) {
        boolean little = data[tiff] == 'I' && data[tiff + 1] == 'I';
        int ifd = tiff + read32(data, tiff + 4, little);
        int entries = read16(data, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                break;
            }
            if (read16(data, entry, little) == 0x0112) {
                int value = read16(data, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    /** Turns the image the way the EXIF orientation says, so it is upright. */
    static BufferedImage upright(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }                       // mirrored
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }                    // upside down
            case 4 -> { t.translate(0, h); t.scale(1, -1); }                       // mirrored, upside down
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }                   // mirrored, turned
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }                // turned left: rotate right
            case 7 -> { t.translate(h, w); t.scale(-1, -1); t.rotate(Math.PI / 2); t.scale(1, -1); } // other diagonal
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }               // turned right: rotate left
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, t, null);
        g.dispose();
        return out;
    }

    // ---------------- grey + dark mode ----------------

    /** Grey, and inverted when the image is mostly dark (light text on a dark background). */
    static BufferedImage greyForOcr(BufferedImage image) {
        BufferedImage grey = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        WritableRaster raster = grey.getRaster();
        int w = raster.getWidth();
        int h = raster.getHeight();
        int[] row = new int[w];
        long sum = 0;
        for (int y = 0; y < h; y++) {
            raster.getSamples(0, y, w, 1, 0, row);
            for (int v : row) {
                sum += v;
            }
        }
        if (sum / Math.max(1L, (long) w * h) >= DARK_BELOW) {
            return grey;
        }
        for (int y = 0; y < h; y++) {
            raster.getSamples(0, y, w, 1, 0, row);
            for (int x = 0; x < w; x++) {
                row[x] = 255 - row[x];
            }
            raster.setSamples(0, y, w, 1, 0, row);
        }
        return grey;
    }

    // ---------------- bytes ----------------

    private static int u8(byte[] data, int i) {
        return data[i] & 0xFF;
    }

    private static boolean startsWith(byte[] data, int pos, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (data[pos + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int read16(byte[] d, int i, boolean little) {
        return little ? u8(d, i) | (u8(d, i + 1) << 8) : (u8(d, i) << 8) | u8(d, i + 1);
    }

    private static int read32(byte[] d, int i, boolean little) {
        return little
                ? u8(d, i) | (u8(d, i + 1) << 8) | (u8(d, i + 2) << 16) | (u8(d, i + 3) << 24)
                : (u8(d, i) << 24) | (u8(d, i + 1) << 16) | (u8(d, i + 2) << 8) | u8(d, i + 3);
    }
}
