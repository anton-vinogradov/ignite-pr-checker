package com.github.igniteprchecker.web;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Where a Russian page may break a line between a number and its unit: "89 / с назад", "(1 / ч)". English writes
 * "89s ago" and "(1h)" in one word; Russian needs a no-break space there to keep the two on one line.
 */
final class SplitUnits {
    /** A digit, a plain space and a unit of time or size, or the word that counts how many times. */
    private static final Pattern SPLIT =
        Pattern.compile("\\d (?:мс|с|мин|минут|секунд|ч|д|день|дня|дней|МБ|раз|раза)(?![а-яё])");

    private SplitUnits() {
    }

    /**
     * Each place in the texts of {@code html} where a number stands apart from its unit, with the text around it. A
     * tag ends a text: a card's number and the words in the element after it are not one phrase.
     */
    static List<String> in(String html) {
        String text = html.replaceAll("<[^>]*>", "\u0000");

        return SPLIT.matcher(text).results()
            .map(m -> text.substring(Math.max(0, m.start() - 20), Math.min(text.length(), m.end() + 10)))
            .toList();
    }
}
