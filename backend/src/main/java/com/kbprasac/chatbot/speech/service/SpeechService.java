package com.kbprasac.chatbot.speech.service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kbprasac.chatbot.shared.entity.ChatEvent;
import com.kbprasac.chatbot.shared.exception.BadRequestException;
import com.kbprasac.chatbot.shared.exception.NotFoundException;
import com.kbprasac.chatbot.shared.repository.ChatEventRepository;

/**
 * Voice for the widget. The transcript goes back to the customer's input box (they review and send it through
 * the normal chat path, so masking applies). Read-aloud only speaks bot replies stored for that session,
 * so the public endpoint cannot be used to synthesize arbitrary text. No audio is stored.
 */
@Service
public class SpeechService {

    /** Formats browsers record (Chrome / Firefox: WebM or Ogg Opus, Safari: MP4/AAC) plus common files. */
    static final Set<String> ALLOWED_AUDIO = Set.of(
            "audio/webm", "audio/ogg", "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac",
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/flac");

    /** Keep read-aloud requests bounded; bot replies are normally far shorter. */
    static final int MAX_SPEECH_CHARS = 2000;

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])[*_](\\S(?:.*?\\S)?)[*_](?![\\w*])");
    private static final Pattern CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern LINE_MARKERS = Pattern.compile("(?m)^\\s{0,3}(?:#{1,6}\\s+|>\\s?|[-*+]\\s+)");

    private final SpeechPort speech;
    private final ChatEventRepository chatEventRepository;

    public SpeechService(SpeechPort speech, ChatEventRepository chatEventRepository) {
        this.speech = speech;
        this.chatEventRepository = chatEventRepository;
    }

    public String transcribe(byte[] audio, String contentType) {
        if (audio == null || audio.length == 0) {
            throw new BadRequestException("Empty recording");
        }
        String mimeType = normalizeMimeType(contentType);
        if (!ALLOWED_AUDIO.contains(mimeType)) {
            throw new BadRequestException("Unsupported audio format");
        }
        return speech.transcribe(audio, mimeType);
    }

    @Transactional(readOnly = true)
    public byte[] readAloud(String sessionId, UUID replyId) {
        ChatEvent event = chatEventRepository.findByIdAndSessionId(replyId, sessionId)
                .filter(e -> e.getBotReply() != null && !e.getBotReply().isBlank())
                .orElseThrow(() -> new NotFoundException("Reply not found"));
        String text = plainText(event.getBotReply());
        if (text.length() > MAX_SPEECH_CHARS) {
            text = text.substring(0, MAX_SPEECH_CHARS);
        }
        return speech.synthesize(text);
    }

    /** "audio/webm;codecs=opus" → "audio/webm" */
    static String normalizeMimeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /** Bot replies are markdown; read the words, not the asterisks. */
    static String plainText(String markdown) {
        String text = IMAGE.matcher(markdown).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1");
        text = BOLD.matcher(text).replaceAll("$2");
        text = ITALIC.matcher(text).replaceAll("$1");
        text = CODE.matcher(text).replaceAll("$1");
        text = LINE_MARKERS.matcher(text).replaceAll("");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
