package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The login card broke its slogan with {@code <br>} at fixed places, put the project line between the slogan and the
 * field, and stacked four paragraphs about the token under the field, so the fact to read before pasting a token,
 * that the server reads TeamCity with it for everyone's PRs, stood in the middle of a wall. One line over the field
 * says that; the other facts about the token are in a fold; the project line is the footer and no longer calls the
 * tool a CI.
 */
class LoginCardPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    @Test
    void theCardReadsIntroLineFieldFoldsFooter() throws IOException {
        String card = card();

        assertThat(card.indexOf("<p class=\"login-intro\">")).isNotNegative()
            .isLessThan(card.indexOf("<p id=\"loginTokenNote\""));
        assertThat(card.indexOf("<p id=\"loginTokenNote\"")).isLessThan(card.indexOf("<input id=\"token\""));
        assertThat(card.indexOf("<div id=\"loginErr\"")).isLessThan(card.indexOf("<details id=\"loginTokenMore\""));
        assertThat(card.indexOf("<details id=\"loginTokenMore\""))
            .isLessThan(card.indexOf("<summary>No ci2 account or token yet?</summary>"));
        assertThat(card.indexOf("<summary>No ci2 account or token yet?</summary>"))
            .isLessThan(card.indexOf("<p class=\"login-foot muted\">"));
        assertThat(card.substring(card.lastIndexOf("</p>"))).startsWith("</p>\n        </section>");
    }

    @Test
    void theIntroHasNoForcedBreaks() throws IOException {
        String intro = textOf("<p class=\"login-intro\">");

        assertThat(card()).doesNotContain("<br>");
        assertThat(intro).isEqualTo("See which tests your Apache Ignite PR actually broke, not the ones already failing "
            + "or flaky on master. Start a RunAll or rerun just the blockers, right from here.");
    }

    @Test
    void theOneLineOverTheFieldSaysTheServerReadsForEveryone() throws IOException {
        assertThat(textOf("<p id=\"loginTokenNote\"")).isEqualTo("Log in with your own TeamCity (ci2) access token. "
            + "There is no shared account: TeamCity records the runs you start or cancel here as yours. For about an "
            + "hour after your last request, the server keeps your token in memory. It uses the token only to read "
            + "from TeamCity, to check open PRs for everyone.");
    }

    /** The fold said again what the line over the field says: that the server reads TeamCity with the token. */
    @Test
    void theFoldHoldsTheOtherFactsAboutTheToken() throws IOException {
        String fold = card().substring(card().indexOf("<details id=\"loginTokenMore\""));
        fold = fold.substring(0, fold.indexOf("</details>"));

        assertThat(fold).contains("<summary>What the checker does with your token</summary>");
        assertThat(fold.split("<li>")).hasSize(4);
        assertThat(fold.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " "))
            .contains("Your browser keeps it in an encrypted session cookie.")
            .doesNotContain("For about an hour", "in memory")
            .contains("After login, the ⚙ menu has options that work while this page is closed. While one is on, the "
                + "server stores the token encrypted, until you turn them all off. Copies stay in server backups until "
                + "the backups are replaced. With any option other than PR commands on, the server also keeps using "
                + "the token to read from TeamCity, to check open PRs for everyone.")
            .contains("Your login ends when you log out (in every browser at once) or after 400 days without use.");
    }

    @Test
    void theFooterNamesTheProjectAndItsCiServer() throws IOException {
        String foot = textOf("<p class=\"login-foot muted\">");

        assertThat(foot).isEqualTo("An open-source developer tool for Apache Ignite contributors (source on GitHub). "
            + "It reads test results from Ignite's CI server, TeamCity (ci2).");
        assertThat(card()).doesNotContain("continuous-integration", "pre-existing flaky");
    }

    private static String card() throws IOException {
        String html = Files.readString(PAGE, UTF_8);
        int start = html.indexOf("<section id=\"loginView\"");
        return html.substring(start, html.indexOf("</section>", start) + "</section>".length());
    }

    /** The text of the element that opens with this tag, its tags dropped and its whitespace collapsed. */
    private static String textOf(String opening) throws IOException {
        String card = card();
        int start = card.indexOf(opening);
        assertThat(start).as(opening).isNotNegative();
        int end = card.indexOf("</p>", start);
        return card.substring(card.indexOf('>', start) + 1, end).replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").strip();
    }
}
