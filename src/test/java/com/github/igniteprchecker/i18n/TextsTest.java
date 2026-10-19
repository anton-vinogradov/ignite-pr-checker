package com.github.igniteprchecker.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The server writes a text of ⚙ from the same catalog the page reads: the English key with its values put in, or its
 * Russian, the word after a number in its Russian form. A string the catalog lacks reads in English rather than not
 * at all.
 */
class TextsTest {
    @Test
    void aKeyReadsInEnglishOrInItsRussian() {
        assertThat(Texts.t(Lang.EN, "Posted a visa to {0} for PR #{1}, RunAll {2}.", "IGNITE-1", 13575, 9001L))
            .isEqualTo("Posted a visa to IGNITE-1 for PR #13575, RunAll 9001.");
        assertThat(Texts.t(Lang.RU, "Posted a visa to {0} for PR #{1}, RunAll {2}.", "IGNITE-1", 13575, 9001L))
            .isEqualTo("Отправлена виза в IGNITE-1 для PR #13575, RunAll 9001.");
        assertThat(Texts.t(Lang.RU, "A sentence no catalog has, {0}.", "$1")).isEqualTo("A sentence no catalog has, $1.");
    }

    @Test
    void aNumberTakesTheRussianFormOfItsWord() {
        String[] forms = {"тест", "теста", "тестов"};

        assertThat(IntStream.of(1, 2, 4, 5, 11, 14, 21, 22, 25, 101, 111).mapToObj(n -> Texts.form(n, forms)))
            .containsExactly("тест", "теста", "теста", "тестов", "тестов", "тестов", "тест", "теста", "тестов", "тест",
                "тестов");
    }

    @Test
    void aLanguageIsKnownByItsCode() {
        assertThat(Lang.of("ru")).isEqualTo(Lang.RU);
        assertThat(Lang.of("en")).isEqualTo(Lang.EN);
        assertThat(Lang.of(null)).isEqualTo(Lang.EN);
        assertThat(Lang.of("de")).isEqualTo(Lang.EN);
        assertThat(Lang.RU.code()).isEqualTo("ru");
    }
}
