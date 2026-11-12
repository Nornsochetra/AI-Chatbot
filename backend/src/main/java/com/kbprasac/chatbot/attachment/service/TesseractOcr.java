package com.kbprasac.chatbot.attachment.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.config.AttachmentProperties;

/**
 * OCR with a Tesseract program installed on this server ({@code OCR_ENGINE=program}, e.g. a Linux server with
 * {@code apt install tesseract-ocr tesseract-ocr-khm}). It is run as a separate process, so a crash or a hang in
 * Tesseract cannot take the backend down; a slow run is killed at the timeout. The image is written to a temporary
 * file for Tesseract and deleted straight after. The default engine is {@link EmbeddedTesseractOcr}.
 */
@Component
@ConditionalOnProperty(name = "app.attachment.ocr.engine", havingValue = "program")
public class TesseractOcr implements OcrPort {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcr.class);

    private final AttachmentProperties.Ocr config;

    public TesseractOcr(AttachmentProperties props) {
        this.config = props.ocr();
    }

    /** Says in the log at startup whether OCR is on and Tesseract can be found. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkInstalled() {
        if (!config.enabled()) {
            log.info("OCR is OFF (OCR_ENABLED=false): files sent to the AI chat are not read; the bot offers an agent.");
            return;
        }
        try {
            Process process = new ProcessBuilder(config.tesseractPath(), "--list-langs")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                log.warn("OCR is on, but '{} --list-langs' failed: {}", config.tesseractPath(), output.strip());
                return;
            }
            for (String language : config.languages().split("\\+")) {
                if (!output.lines().map(String::strip).toList().contains(language)) {
                    log.warn("OCR is on, but Tesseract has no '{}' language data. Install {}.traineddata", language,
                            language);
                }
            }
            log.info("OCR is ON (Tesseract, languages {})", config.languages());
        } catch (IOException e) {
            log.warn("OCR is on, but Tesseract was not found at '{}'. Set OCR_TESSERACT_PATH. Files will count as "
                    + "unreadable until then.", config.tesseractPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String read(BufferedImage image, Layout layout, Duration timeout) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("ocr-", ".png");
            output = Files.createTempFile("ocr-", ".txt");
            ImageIO.write(image, "png", input.toFile());
            Process process = new ProcessBuilder(command(input, layout))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new OcrException("Tesseract took longer than " + timeout);
            }
            if (process.exitValue() != 0) {
                throw new OcrException("Tesseract exited with " + process.exitValue());
            }
            return OcrLines.confident(lines(Files.readString(output, StandardCharsets.UTF_8)), config.minConfidence());
        } catch (IOException e) {
            throw new OcrException("Tesseract could not be run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("Interrupted", e);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    /**
     * Tesseract's TSV output: one row per word with block / paragraph / line numbers and a confidence; a line's
     * confidence is the mean of its words.
     */
    static List<OcrLines.Line> lines(String tsv) {
        java.util.Map<String, List<String[]>> byLine = new java.util.LinkedHashMap<>();
        tsv.lines().skip(1).map(row -> row.split("\t", -1))
                .filter(cols -> cols.length >= 12 && "5".equals(cols[0]) && !cols[11].isBlank())
                .forEach(cols -> byLine.computeIfAbsent(cols[1] + "." + cols[2] + "." + cols[3] + "." + cols[4],
                        key -> new ArrayList<>()).add(cols));
        return byLine.values().stream().map(words -> new OcrLines.Line(
                        String.join(" ", words.stream().map(cols -> cols[11]).toList()),
                        (float) words.stream().mapToDouble(cols -> Double.parseDouble(cols[10])).average().orElse(0)))
                .toList();
    }

    /** tesseract &lt;image&gt; stdout -l khm+eng --psm 11 [--tessdata-dir dir] tsv */
    private List<String> command(Path input, Layout layout) {
        List<String> command = new ArrayList<>(List.of(config.tesseractPath(), input.toString(), "stdout",
                "-l", config.languages(), "--psm", String.valueOf(layout.pageSegMode)));
        if (config.tessdataDir() != null && !config.tessdataDir().isBlank()) {
            command.add("--tessdata-dir");
            command.add(config.tessdataDir());
        }
        command.add("tsv");
        return command;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete the OCR temp file {}", path);
        }
    }
}
