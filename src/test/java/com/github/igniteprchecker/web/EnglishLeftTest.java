package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The check of a Russian page leaves alone only the English the Russian tour keeps itself, and the names of the
 * scenario; a phrase of the English page left untranslated, in a text or a tooltip, is caught.
 */
class EnglishLeftTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theTermsLeftInEnglishAreTheRussianToursOwn() throws Exception {
        String tour = Files.readString(Path.of("docs/features.ru.md"), UTF_8);

        assertThat(EnglishLeft.TERMS).allSatisfy(term -> assertThat(tour).contains(term));
    }

    @Test
    void aPhraseLeftInEnglishIsCaughtAndATermIsNot() {
        String html = "<span title=\"Restart the service — it will be unavailable for ~10 seconds\">Перезапустить</span>";
        List<String> left = EnglishLeft.in(JSON.valueToTree(Map.of("card",
            "Ignite PR Checker: RunAll на master, 3 мин назад · Last refresh · Move MDC to JUnit")), html,
            List.of("Move MDC to JUnit"));

        assertThat(left).containsExactly("card: «Last refresh» in card: Ignite PR Checker: RunAll на master, 3 мин назад"
            + " · Last refresh · Move MDC to JUnit", "title: «Restart the service» in title: Restart the service — it "
            + "will be unavailable for ~10 seconds", "title: «it will be unavailable for» in title: Restart the "
            + "service — it will be unavailable for ~10 seconds");
    }
}
