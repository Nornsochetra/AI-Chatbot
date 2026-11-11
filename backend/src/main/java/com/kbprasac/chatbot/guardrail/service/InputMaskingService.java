package com.kbprasac.chatbot.guardrail.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Masks sensitive values before text leaves our system (requirement: the chatbot must not receive OTP, PIN,
 * password, CVV, full account / card numbers or personal data).
 * Khmer digits are treated the same as ASCII digits. Keywords are kept so the bot still understands
 * the context ("my OTP is [REDACTED]") and can warn the user.
 */
@Service
public class InputMaskingService {

    public static final String MASK = "[REDACTED]";

    private static final String DIGIT = "[0-9\u17E0-\u17E9]";
    private static final String NOT_DIGIT = "[^0-9\u17E0-\u17E9\\n]";

    /** At least {@code min} digits, optionally separated by single spaces or dashes. */
    private static String digits(int min) {
        return DIGIT + "(?:[ -]?" + DIGIT + "){" + (min - 1) + ",}";
    }

    // Khmer has no word boundaries in regex, so Khmer keywords are matched without \b.
    private static final String SECRET_KEYWORDS =
            "\\b(?:otp|pin|cvv|cvc|password|passcode|security code|verification code)\\b"
                    + "|លេខសម្ងាត់|ពាក្យសម្ងាត់|លេខកូដ|កូដសម្ងាត់";

    private static final String ID_KEYWORDS =
            "\\b(?:account|acc|a/c|card|national id|id card|id number|passport)\\b"
                    + "|លេខគណនី|គណនី|លេខកាត|កាត|អត្តសញ្ញាណប័ណ្ណ|លិខិតឆ្លងដែន";

    // secret keyword + any digits after it (3+), e.g. "OTP 123456", "លេខសម្ងាត់ខ្ញុំគឺ ៤៨២១"
    private static final Pattern SECRET_WITH_KEYWORD = Pattern.compile(
            "(?iu)(" + SECRET_KEYWORDS + ")(" + NOT_DIGIT + "{0,20})" + digits(3));

    // "password is hunter2": the word after "password ... is / : / = / គឺ / ជា" is masked only when it
    // contains a digit or symbol, so "my password is not working" stays readable
    private static final String PASSWORD_KEYWORDS = "\\b(?:password|passcode|pwd)\\b|ពាក្យសម្ងាត់|លេខសម្ងាត់";
    private static final String SECRET_CHAR = "[0-9០-៩@#$%^&*_+=~|\\\\/<>\\[\\]{}()-]";
    private static final Pattern TEXT_PASSWORD = Pattern.compile(
            "(?iu)(" + PASSWORD_KEYWORDS + ")([^\\n]{0,20}?(?:\\bis\\b|:|=|គឺ|ជា)\\s*)"
                    + "(?=[^\\s។?!,]*" + SECRET_CHAR + ")[^\\s។?!,]+");

    // secret keyword + 3+ number words, e.g. "pin is four eight two one", "លេខសម្ងាត់ បួន ប្រាំបី ពីរ មួយ"
    // (longer Khmer words first so ប្រាំបី is not read as ប្រាំ)
    private static final String NUMBER_WORD =
            "(?:\\b(?:zero|one|two|three|four|five|six|seven|eight|nine)\\b"
                    + "|សូន្យ|ប្រាំមួយ|ប្រាំពីរ|ប្រាំបី|ប្រាំបួន|ប្រាំ|មួយ|ពីរ|បី|បួន)";
    private static final Pattern SECRET_NUMBER_WORDS = Pattern.compile(
            "(?iu)(" + SECRET_KEYWORDS + ")(" + NOT_DIGIT + "{0,20}?)"
                    + NUMBER_WORD + "(?:[\\s,-]*" + NUMBER_WORD + "){2,}");

    // account / card / ID / passport keyword + 7+ digits (optional leading letter for passports)
    private static final Pattern ID_WITH_KEYWORD = Pattern.compile(
            "(?iu)(" + ID_KEYWORDS + ")(" + NOT_DIGIT + "{0,20})[A-Za-z]?" + digits(7));

    // Cambodian phone numbers: 0xx xxx xxx(x) or +855 xx xxx xxx(x), ASCII or Khmer digits
    private static final Pattern PHONE = Pattern.compile(
            "(?<!" + DIGIT + ")(?:\\+?(?:855|៨៥៥)[ -]?|[0០])" + DIGIT + "{2}[ -]?" + DIGIT + "{3}[ -]?"
                    + DIGIT + "{3,4}(?!" + DIGIT + ")");

    // 13+ digits anywhere (card / account numbers without a keyword)
    private static final Pattern LONG_NUMBER = Pattern.compile(digits(13));

    /** Full masking, for text sent to the bot (outside the bank): secrets, card / account / ID numbers, phones. */
    public MaskResult mask(String text) {
        String masked = maskSecretsIn(text);
        masked = ID_WITH_KEYWORD.matcher(masked).replaceAll("$1$2" + MASK);
        masked = PHONE.matcher(masked).replaceAll(MASK);
        return new MaskResult(masked, !masked.equals(text));
    }

    /**
     * Secrets only, for text sent to a live agent: the requirements let agents collect personal data (phone, ID)
     * in the chat, but OTP / PIN / password / CVV and card numbers must never be exposed.
     */
    public MaskResult maskSecrets(String text) {
        String masked = maskSecretsIn(text);
        return new MaskResult(masked, !masked.equals(text));
    }

    private static String maskSecretsIn(String text) {
        String masked = SECRET_WITH_KEYWORD.matcher(text).replaceAll("$1$2" + MASK);
        masked = TEXT_PASSWORD.matcher(masked).replaceAll("$1$2" + MASK);
        masked = SECRET_NUMBER_WORDS.matcher(masked).replaceAll("$1$2" + MASK);
        return LONG_NUMBER.matcher(masked).replaceAll(MASK);
    }

    public record MaskResult(String text, boolean changed) {
    }
}
