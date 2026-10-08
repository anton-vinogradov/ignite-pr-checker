package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.igniteprchecker.config.WarmProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The login card said the TeamCity token is "kept encrypted in your session cookie, never on the server", and the
 * visa's JIRA panel said "never stored server-side". The server holds every visitor's TeamCity token in memory for an
 * hour to warm open PRs for everyone, keeps it encrypted while an option is on, and keeps the JIRA PAT of an Auto visa
 * until the visa is posted. Each note now says where the token goes at the moment it is pasted.
 */
class TokenNotesPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    @Test
    void theLoginCardSaysTheServerHoldsTheToken() throws IOException {
        String note = textOf("loginTokenNote");

        assertThat(note).doesNotContain("never on the server")
            .contains("The token is kept encrypted in your session cookie. For about an hour after your last request "
                + "the server also holds it in memory and uses it only to read from TeamCity, to pre-analyse open PRs "
                + "for everyone.")
            .contains("Turn on an option in ⚙ and the server stores the token encrypted until you turn the options "
                + "off. With any option other than PR commands on, it also keeps doing those reads.");
        assertThat(new WarmProperties(null, null, null, null).tokenTtlMinutes()).as("about an hour").isEqualTo(60);
    }

    /** The tours said any standing option keeps the token in the pool, but PR commands alone lends it to nothing. */
    @Test
    void theToursSayPrCommandsAloneLendsTheTokenToNothing() throws IOException {
        assertThat(collapsed("docs/features.md"))
            .contains("any **standing option** other than PR commands alone keeps yours in the pool while it is on");
        assertThat(collapsed("docs/features.ru.md"))
            .contains("любая **standing-опция**, кроме одних PR commands, держит твой токен в пуле, пока она включена");
    }

    /** The PATs pasted in ⚙ are for the options, which store them. */
    @Test
    void theSettingsPanelsSayTheOptionsStoreTheirTokens() throws IOException {
        assertThat(textOf("settingsJira")).contains("It goes into your session cookie, encrypted, and the server "
            + "stores it encrypted while auto-visa is on.");
        assertThat(textOf("settingsGh")).contains("It goes into your session cookie, encrypted, and the server "
            + "stores it encrypted while an option that needs it is on.");
        assertThat(Files.readString(PAGE, UTF_8)).doesNotContain("never stored server-side");
    }

    @Test
    void theJiraPanelSaysWhereThePatGoesForEachVisa() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            await page.el('visaBtn').onclick();
            const visa = page.el('jiraKept').textContent;
            await page.el('jiraCancel').onclick();
            await page.el('autoVisaBtn').onclick();
            report({ visa, auto: page.el('jiraKept').textContent,
                shown: !page.el('jiraPanel').classList.contains('hidden') });
            """);

        assertThat(out.get("visa").asText()).isEqualTo("It stays in your session cookie only, encrypted.");
        assertThat(out.get("auto").asText()).isEqualTo("It goes into your session cookie, encrypted, and the server "
            + "stores it encrypted until this visa is posted.");
        assertThat(out.get("shown").asBoolean()).isTrue();
    }

    private static String collapsed(String file) throws IOException {
        return Files.readString(Path.of(file), UTF_8).replaceAll("\\s+", " ");
    }

    /** The text of the element with this id, its tags dropped and its whitespace collapsed. */
    private static String textOf(String id) throws IOException {
        String html = Files.readString(PAGE, UTF_8);
        int start = html.indexOf("id=\"" + id + "\"");
        assertThat(start).as(id).isNotNegative();
        int open = html.lastIndexOf('<', start);
        String tag = html.substring(open + 1, html.indexOf(' ', open));
        int end = html.indexOf("</" + tag + ">", start);

        return html.substring(html.indexOf('>', start) + 1, end).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ")
            .replace(" .", ".").strip();
    }
}
