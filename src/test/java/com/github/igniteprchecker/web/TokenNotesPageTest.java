package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.WarmProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The login card said the TeamCity token is "kept encrypted in your session cookie, never on the server", and the
 * visa's JIRA panel said "never stored server-side". The server holds every visitor's TeamCity token in memory for an
 * hour to warm open PRs for everyone, and keeps it and the JIRA token encrypted while an option is on. Each note now
 * says where the token goes at the moment it is pasted.
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

        assertThat(note).doesNotContain("never on the server", "memory", "swap")
            .contains("The server also uses your token to read TeamCity for everyone's PRs, for up to an hour after "
                + "your last request.");
        assertThat(fold).contains("Your browser keeps it in an encrypted session cookie.")
            .contains("The server keeps it in memory for up to an hour after your last request, or until you log out. "
                + "The host's swap can write that memory to the disk.")
            .contains("While one is on, the server stores the token encrypted, until you turn them all off. Copies "
                + "stay in server backups for 2 days. With any option other than PR commands on, "
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
     * The PATs pasted in ⚙ are for the options, which store them; neither rides in a cookie. ⚙ says the first in one
     * line, and the tour says where each token goes.
     */
    @Test
    void theSettingsPanelsSayTheOptionsStoreTheirTokens() throws IOException {
        assertThat(Files.readString(PAGE, UTF_8).replaceAll("\\s+", " ")).contains("The server stores these tokens "
            + "encrypted, and only while an option that needs them is on.").doesNotContain("never stored server-side");
        assertThat(collapsed("docs/features.md")).contains("Save switches on **Visa my runs in JIRA** below with the "
            + "token. The server stores the token encrypted while the option is on. No cookie keeps it.")
            .contains("The page sends the token with the switch, and no cookie keeps it.")
            .doesNotContain("It goes into your session cookie");
        assertThat(collapsed("docs/features.ru.md")).contains("Save включает опцию **Visa my runs in JIRA** ниже с "
            + "этим токеном, а сервер хранит токен зашифрованным, пока опция включена. Ни одна cookie его не хранит.")
            .contains("Страница отправляет токен вместе с переключателем, и ни одна cookie его не хранит.")
            .doesNotContain("Он ложится в cookie твоей сессии");
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
