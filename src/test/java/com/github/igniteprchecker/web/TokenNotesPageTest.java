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

    /**
     * The one line over the field says the server reads TeamCity with the token for everyone; the fold under the field
     * says where else the token goes.
     */
    @Test
    void theLoginCardSaysTheServerHoldsTheToken() throws IOException {
        String note = textOf("loginTokenNote");
        String fold = textOf("loginTokenMore");

        assertThat(note).doesNotContain("never on the server")
            .contains("Until you log out, but no longer than about an hour after your last request, the server keeps "
                + "your token in memory. The host's swap can write that memory to the disk. The server uses the token "
                + "only to read from TeamCity, to check open PRs for everyone.");
        assertThat(fold).contains("Your browser keeps it in an encrypted session cookie.")
            .contains("While one is on, the server stores the token encrypted, until you turn them all off. Copies "
                + "stay in server backups until the backups are replaced. With any option other than PR commands on, "
                + "the server also keeps using the token to read from TeamCity, to check open PRs for everyone.");
        assertThat(new WarmProperties(null, null, null, null).tokenTtlMinutes()).as("about an hour").isEqualTo(60);
    }

    /** The tours said any standing option keeps the token in the pool, but PR commands alone lends it to nothing. */
    @Test
    void theToursSayPrCommandsAloneLendsTheTokenToNothing() throws IOException {
        assertThat(collapsed("docs/features.md"))
            .contains("any **standing option** other than PR commands alone keeps yours in the pool while it is on");
        assertThat(collapsed("docs/features.ru.md"))
            .contains("любая **постоянная опция**, кроме одних PR commands, держит твой токен в пуле, пока она "
                + "включена");
    }

    /**
     * The PATs pasted in ⚙ are for the options, which store them; the GitHub one rides in no cookie. ⚙ says the first
     * in one line, and the tour says where each token goes.
     */
    @Test
    void theSettingsPanelsSayTheOptionsStoreTheirTokens() throws IOException {
        assertThat(Files.readString(PAGE, UTF_8).replaceAll("\\s+", " ")).contains("The server stores these tokens "
            + "encrypted, and only while an option that needs them is on.").doesNotContain("never stored server-side");
        assertThat(collapsed("docs/features.md")).contains("It goes into your session cookie, encrypted, and the "
            + "server stores it encrypted while the option below is on.")
            .contains("The page sends the token with the switch, and no cookie keeps it.");
        assertThat(collapsed("docs/features.ru.md")).contains("Он ложится в cookie твоей сессии, зашифрованным, а "
            + "сервер хранит его зашифрованным, пока включена опция ниже.")
            .contains("Страница отправляет токен вместе с переключателем, и ни одна cookie его не хранит.");
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

        assertThat(out.get("visa").asText()).isEqualTo("It goes into your session cookie, encrypted. While Visa my "
            + "runs in JIRA is on in ⚙, your next change there also stores it on the server.");
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
