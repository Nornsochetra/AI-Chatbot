package com.kbprasac.chatbot.chat.service;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.kbprasac.chatbot.shared.enums.HandoverReason;

/**
 * BRD auto-route: the chat goes to a live agent without the customer pressing the button when the message shows an
 * urgent support need, or that a human agent is required. (The third BRD trigger, repeated failed answers, is in
 * {@link ChatService}.) Khmer has no word boundaries in regex, so Khmer phrases are matched without \b.
 */
@Component
public class AutoRouteDetector {

    // fraud, scams, lost / stolen cards, hacked accounts, money gone: needs a person at once (e.g. to block a card)
    private static final Pattern URGENT = Pattern.compile("(?iu)"
            + "\\b(?:fraud\\w*|scam\\w*|stolen|hack(?:ed|er|ing)?|unauthori[sz]ed"
            + "|lost (?:my )?(?:card|phone|atm card)|card (?:is |was |got )?(?:lost|stolen|blocked)"
            + "|money (?:is |was )?(?:missing|gone|disappeared|lost)|lost (?:my |some |the |all )?(?:money|funds)"
            + "|(?:sent|transferr?ed|paid)(?: (?:money|it))? to (?:the |a )?wrong (?:account|person|number)"
            + "|wrong (?:account|transfer)"
            + "|(?:did ?n[o']?t|never) (?:make|do|send) (?:this|that|the|a|these) (?:transfer|transaction|payment)s?)\\b"
            + "|ឆបោក|ឆក់ប្រាក់|លួច|បាត់កាត|កាតបាត់|ហេក|លុយបាត់|ប្រាក់បាត់|បាត់លុយ|បាត់ប្រាក់|ផ្ទេរខុស");

    // the customer asks for a person in words, instead of pressing the button. "agent" alone is not enough:
    // "agent" also means a bank agent location ("where is the nearest agent?")
    private static final Pattern ASKED_FOR_AGENT = Pattern.compile("(?iu)"
            + "\\b(?:talk|speak|chat|connect(?: me)?)(?: to| with) (?:an? |the |your )?(?:live |real |human )?"
            + "(?:agent|person|human|staff|someone|somebody|officer|representative|operator)\\b"
            + "|\\b(?:live|human|real) (?:agent|person|staff)\\b"
            + "|ភ្នាក់ងារផ្ទាល់|និយាយជាមួយមនុស្ស|និយាយជាមួយបុគ្គលិក|និយាយជាមួយភ្នាក់ងារ|ជជែកជាមួយបុគ្គលិក");

    /** Why this message should go to a live agent right away, if it should. */
    public Optional<HandoverReason> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (URGENT.matcher(text).find()) {
            return Optional.of(HandoverReason.AUTO_URGENT);
        }
        if (ASKED_FOR_AGENT.matcher(text).find()) {
            return Optional.of(HandoverReason.AUTO_ASKED_FOR_AGENT);
        }
        return Optional.empty();
    }
}
