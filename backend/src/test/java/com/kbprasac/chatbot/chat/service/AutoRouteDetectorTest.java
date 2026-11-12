package com.kbprasac.chatbot.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.kbprasac.chatbot.shared.enums.HandoverReason;

class AutoRouteDetectorTest {

    private final AutoRouteDetector detector = new AutoRouteDetector();

    @ParameterizedTest
    @ValueSource(strings = {
            "I think this is fraud",
            "someone scammed me on telegram",
            "my card was stolen",
            "I lost my card yesterday",
            "my account got hacked",
            "there is an unauthorized transaction",
            "I didn't make this transfer",
            "money is missing from my account",
            "I lost money during a transfer",
            "I sent money to the wrong account",
            "ខ្ញុំបាត់លុយពេលផ្ទេរប្រាក់",
            "ខ្ញុំផ្ទេរខុសគណនី",
            "ខ្ញុំត្រូវគេឆបោក",
            "ខ្ញុំបាត់កាត",
            "គេលួចលុយពីគណនីខ្ញុំ"
    })
    void urgentNeeds(String text) {
        assertEquals(Optional.of(HandoverReason.AUTO_URGENT), detector.detect(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "can I talk to a real person",
            "I want to speak with someone",
            "connect me to an agent please",
            "live agent",
            "ខ្ញុំចង់និយាយជាមួយភ្នាក់ងារផ្ទាល់",
            "សូមឱ្យខ្ញុំនិយាយជាមួយបុគ្គលិក"
    })
    void askingForAPerson(String text) {
        assertEquals(Optional.of(HandoverReason.AUTO_ASKED_FOR_AGENT), detector.detect(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What documents do I need to open an account?",
            "Where is the nearest agent?",
            "How do I become a bank agent?",
            "What is your customer service number?",
            "How do I block my card in the app?",
            "តើសាខាបើកម៉ោងប៉ុន្មាន?",
            "តើភ្នាក់ងារនៅជិតបំផុតនៅឯណា?"
    })
    void normalQuestionsStayWithTheBot(String text) {
        assertTrue(detector.detect(text).isEmpty(), text);
    }
}
