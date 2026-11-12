package com.kbprasac.chatbot.attachment.service;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.config.AttachmentProperties;

import jakarta.annotation.PreDestroy;
import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;

/**
 * OCR with Tesseract bundled as a Java library (Tess4J, from Maven Central): nothing to install on Windows x64,
 * only the language data files ({@code eng.traineddata}, {@code khm.traineddata}) in {@code OCR_TESSDATA_DIR}
 * (default: {@code backend/tessdata}). Default engine ({@code OCR_ENGINE=embedded}); {@link TesseractOcr} runs an
 * installed tesseract program instead ({@code OCR_ENGINE=program}).
 * <p>
 * Tesseract runs inside the backend here, and a native call cannot be stopped: at the timeout the file counts as
 * unreadable while the call finishes in the background. A small pool bounds how many run at once.
 */
@Component
@ConditionalOnProperty(name = "app.attachment.ocr.engine", havingValue = "embedded", matchIfMissing = true)
public class EmbeddedTesseractOcr implements OcrPort {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedTesseractOcr.class);

    /** used when OCR_TESSDATA_DIR is empty; relative to where the backend runs (the backend folder) */
    static final String DEFAULT_TESSDATA = "tessdata";
    private static final int THREADS = 2;

    private final AttachmentProperties.Ocr config;
    private final Path tessdata;
    private final ExecutorService pool;

    public EmbeddedTesseractOcr(AttachmentProperties props) {
        this.config = props.ocr();
        String dir = config.tessdataDir() == null || config.tessdataDir().isBlank()
                ? DEFAULT_TESSDATA : config.tessdataDir();
        this.tessdata = Path.of(dir).toAbsolutePath().normalize();
        AtomicInteger count = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(THREADS, task -> {
            Thread thread = new Thread(task, "ocr-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Says in the log at startup whether OCR is on and ready; a small test read also loads the native library. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkInstalled() {
        if (!config.enabled()) {
            log.info("OCR is OFF (OCR_ENABLED=false): files sent to the AI chat are not read; the bot offers an agent.");
            return;
        }
        List<String> missing = missingLanguages();
        if (!missing.isEmpty()) {
            log.warn("OCR is on, but language data is missing in {}: {}. Download them from "
                    + "https://github.com/tesseract-ocr/tessdata into that folder. Files count as unreadable until then.",
                    tessdata, missing.stream().map(l -> l + ".traineddata").toList());
            return;
        }
        try {
            read(new BufferedImage(64, 32, BufferedImage.TYPE_BYTE_GRAY), Layout.SCREENSHOT, Duration.ofSeconds(30));
            log.info("OCR is ON (Tesseract library, languages {}, data in {})", config.languages(), tessdata);
        } catch (OcrException e) {
            log.warn("OCR is on, but Tesseract could not start: {}. On Windows this usually means the Microsoft "
                    + "Visual C++ runtime is missing.", e.getMessage());
        }
    }

    @Override
    public String read(BufferedImage image, Layout layout, Duration timeout) {
        List<String> missing = missingLanguages();
        if (!missing.isEmpty()) {
            throw new OcrException("no language data for " + missing + " in " + tessdata);
        }
        Future<String> result = pool.submit(() -> {
            // one instance per read: Tesseract instances are not thread-safe
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdata.toString());
            tesseract.setLanguage(config.languages());
            tesseract.setPageSegMode(layout.pageSegMode);
            // screenshots carry no resolution; without a hint Tesseract guesses and warns
            tesseract.setVariable("user_defined_dpi", "300");
            // line by line with confidence, so noise read from icons and photos can be dropped
            List<OcrLines.Line> lines = tesseract.getWords(image, TessPageIteratorLevel.RIL_TEXTLINE).stream()
                    .map((Word line) -> new OcrLines.Line(line.getText(), line.getConfidence()))
                    .toList();
            return OcrLines.confident(lines, config.minConfidence());
        });
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            result.cancel(true);
            throw new OcrException("Tesseract took longer than " + timeout);
        } catch (ExecutionException e) {
            // TesseractException, or an Error such as UnsatisfiedLinkError when the native library cannot load
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new OcrException("Tesseract failed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("Interrupted", e);
        }
    }

    List<String> missingLanguages() {
        return Arrays.stream(config.languages().split("\\+"))
                .map(String::strip)
                .filter(language -> !Files.isRegularFile(tessdata.resolve(language + ".traineddata")))
                .toList();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
