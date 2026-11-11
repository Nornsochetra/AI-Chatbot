package com.kbprasac.chatbot.guardrail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.kbprasac.chatbot.guardrail.service.InputMaskingService.MaskResult;

class InputMaskingServiceTest {

    private final InputMaskingService service = new InputMaskingService();

    // --- must not change normal questions ---

    @ParameterizedTest
    @ValueSource(strings = {
            "What documents do I need to open an account?",
            "តើត្រូវការឯកសារអ្វីខ្លះដើម្បីបើកគណនីសន្សំ?",
            "ចុះប្រាក់ដាក់ដំបូងវិញ?",
            "the fee is 5 dollars per month",
            "Can I borrow 5000 dollars?",
            "តើខ្ញុំអាចខ្ចីប្រាក់ ៥០០០ ដុល្លារបានទេ?",
            "Opening a company account needs 100 dollars",
            "My card ending 1234 is lost",
            "Branch hours are 8:00 to 15:00 in 2026",
            "my password is not working",
            "my password is not working?",
            "I forgot my password, what should I do?",
            "ពាក្យសម្ងាត់ខ្ញុំគឺមិនដំណើរការទេ",
            "I want one savings account and two cards for three people",
            "តើលេខកូដមួយណាដែលត្រូវប្រើ?"
    })
    void leavesNormalTextUntouched(String text) {
        MaskResult r = service.mask(text);
        assertFalse(r.changed(), "should not mask: " + text);
        assertEquals(text, r.text());
    }

    // --- secrets ---

    @Test
    void masksOtpAndPinKeepingKeyword() {
        assertEquals("my OTP is " + InputMaskingService.MASK, service.mask("my OTP is 123456").text());
        assertEquals("pin: " + InputMaskingService.MASK, service.mask("pin: 4821").text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "លេខសម្ងាត់ខ្ញុំគឺ ៤៨២១",
            "ពាក្យសម្ងាត់ 998877",
            "លេខកូដដែលខ្ញុំទទួលបានគឺ 123456",
            "OTP របស់ខ្ញុំគឺ ១២៣៤៥៦",
            "my security code is 123",
            "verification code: 1 2 3 4 5 6"
    })
    void masksSecrets(String text) {
        MaskResult r = service.mask(text);
        assertTrue(r.changed(), "should mask: " + text);
        assertTrue(r.text().endsWith(InputMaskingService.MASK), r.text());
    }

    @Test
    void masksTextPasswordKeepingKeyword() {
        assertEquals("my password is " + InputMaskingService.MASK, service.mask("my password is hunter2").text());
        assertEquals("my password is " + InputMaskingService.MASK + ", can you reset it?",
                service.mask("my password is Abc@xyz, can you reset it?").text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password: Abc@123",
            "passcode = hello99",
            "ពាក្យសម្ងាត់ខ្ញុំគឺ abc123",
            "លេខសម្ងាត់ខ្ញុំជា Pass#word"
    })
    void masksTextPasswords(String text) {
        MaskResult r = service.mask(text);
        assertTrue(r.changed(), "should mask: " + text);
        assertTrue(r.text().endsWith(InputMaskingService.MASK), r.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "my pin is four eight two one",
            "OTP one two three four five six",
            "the verification code is nine, nine, eight",
            "លេខសម្ងាត់ខ្ញុំគឺ បួន ប្រាំបី ពីរ មួយ",
            "លេខកូដ បួនប្រាំបីពីរមួយ"
    })
    void masksSpelledOutSecretNumbers(String text) {
        MaskResult r = service.mask(text);
        assertTrue(r.changed(), "should mask: " + text);
        assertTrue(r.text().endsWith(InputMaskingService.MASK), r.text());
    }

    // --- voice transcripts (speech-to-text writes codes and keywords its own way) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "My OTP is 1, 2, 3, 4, 5, 6",
            "my OTP is 12, 34, 56",
            "pin 4. 8. 2. 1",
            "លេខសម្ងាត់ខ្ញុំគឺ ១, ២, ៣, ៤",
            "The code you sent is 482193",
            "my SMS code is 4821",
            "កូដ 482193",
            "My pen is 4821",
            "my ping is 4821",
            "bin number 4821",
            "my O T P is 482193",
            "o.t.p 482193",
            "OTB 482193"
    })
    void masksVoiceStyleSecrets(String text) {
        MaskResult r = service.mask(text);
        assertTrue(r.changed(), "should mask: " + text);
        assertTrue(r.text().endsWith(InputMaskingService.MASK), r.text());
        assertTrue(service.maskSecrets(text).changed(), "agent must not see it either: " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "My account number is 0, 0, 1, 2, 3, 4, 5, 6, 7",
            "account 001, 234, 567"
    })
    void masksReadOutAccountNumbers(String text) {
        assertTrue(service.mask(text).text().endsWith(InputMaskingService.MASK), service.mask(text).text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "My OTP expired after 10, 15 minutes",
            "error code 404",
            "the code is not working",
            "I need a pen",
            "the OTP came 2 times",
            "Can I open 2 accounts?"
    })
    void leavesVoiceStyleNormalTextUntouched(String text) {
        assertFalse(service.mask(text).changed(), "should not mask: " + text);
    }

    @Test
    void readOutCodeKeepsTheRestOfTheSentence() {
        assertEquals("My OTP is " + InputMaskingService.MASK + ", it does not work",
                service.mask("My OTP is 1, 2, 3, 4, 5, 6, it does not work").text());
    }

    // --- account / card / ID numbers ---

    @Test
    void masksCardNumberWithSpaces() {
        MaskResult r = service.mask("my card is 4111 1111 1111 1111 please help");
        assertTrue(r.changed());
        assertFalse(r.text().contains("4111"));
    }

    @Test
    void masksKhmerDigitCardNumber() {
        assertTrue(service.mask("លេខកាត ៤១១១១១១១១១១១១១១១").changed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "my account number is 001234567",
            "លេខគណនីខ្ញុំ ០០១២៣៤៥៦៧",
            "national id 123456789",
            "អត្តសញ្ញាណប័ណ្ណលេខ 012345678",
            "passport N1234567"
    })
    void masksIdentifiersAfterKeyword(String text) {
        MaskResult r = service.mask(text);
        assertTrue(r.changed(), "should mask: " + text);
        assertTrue(r.text().endsWith(InputMaskingService.MASK), r.text());
    }

    // --- secrets-only level (live agent chats) ---

    @Test
    void secretsOnlyKeepsPersonalDataForAgents() {
        assertFalse(service.maskSecrets("please call me at 012 345 678").changed());
        assertFalse(service.maskSecrets("my national id 123456789").changed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "my OTP is 123456",
            "my password is hunter2",
            "my pin is four eight two one",
            "card 4111 1111 1111 1111"
    })
    void secretsOnlyStillMasksSecrets(String text) {
        assertTrue(service.maskSecrets(text).changed(), "should mask: " + text);
    }

    // --- phone numbers (personal data) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "call me at 012 345 678",
            "my number is 0961234567",
            "+855 12 345 678",
            "លេខទូរស័ព្ទខ្ញុំ ០១២៣៤៥៦៧៨"
    })
    void masksCambodianPhoneNumbers(String text) {
        assertTrue(service.mask(text).changed(), "should mask: " + text);
    }
}
