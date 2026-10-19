package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The token audit of 14.10 found the texts about tokens wrong: a refused GitHub token "switched off the two options"
 * where three went, the autofix used "the same GitHub token as the option above", PR commands, which needs none, the
 * reasons to store the GitHub token left out adding tests to suites, "All off" said the stored tokens were removed
 * while the cookie, the backups and an armed Auto visa kept them, a server without GITHUB_TOKEN was said to read
 * GitHub 60 times an hour when the command poll alone takes those 60, the TeamCity client was said to call "always
 * under the caller's token", and SESSION_SECRET was called the cookie's secret though it encrypts every stored token.
 * Nor did anything advise an expiry for the JIRA and GitHub tokens.
 */
class TokenTextsTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /** The note stands in the group of the GitHub token for commits, over the two options that commit with it. */
    @Test
    void aRefusedGithubTokenNamesTheOptionsItTakes() throws IOException {
        assertThat(collapsed(PAGE)).doesNotContain("the two options")
            .contains("GitHub rejected your saved token, so the options that commit with it went off");
        assertThat(SettingsGroupsTest.group("groupGhToken")).contains("id=\"ghTokenGone\"", "id=\"styleFixToggle\"",
            "id=\"suiteFixToggle\"");
        assertThat(collapsed(Path.of("src/main/java/com/github/igniteprchecker/jira/StandingVisas.java")))
            .doesNotContain("The two options that need this token");
    }

    /** ⚙ says it by the group each option stands in; the tour says it in words. */
    @Test
    void theGithubOptionsSayWhoseTokenTheyUse() throws IOException {
        assertThat(collapsed(PAGE)).doesNotContain("same GitHub token as the option");
        assertThat(SettingsGroupsTest.group("groupGhToken")).contains("<b>GitHub token for commits</b>",
            "id=\"styleFixToggle\"", "id=\"suiteFixToggle\"");
        assertThat(SettingsGroupsTest.group("groupTc")).contains("id=\"ghCommentToggle\"");
        assertThat(collapsed(Path.of("docs/features.md"))).doesNotContain("the same GitHub token")
            .contains("It uses your GitHub token, which the checker keeps only for such commits")
            .contains("Needs no GitHub token of yours.");
        assertThat(collapsed(Path.of("README.md"))).contains("the user's GitHub token for the checkstyle autofix and"
            + " adding tests to suites, the two options that commit");
        assertThat(collapsed(Path.of("README.ru.md"))).contains("его токен GitHub для автоисправления checkstyle и"
            + " добавления тестов в сьюты — двух опций, которые коммитят");
    }

    @Test
    void theTourSaysTheTeamCityTokenGoesWithAnyOption() throws IOException {
        assertThat(collapsed(Path.of("docs/features.md"))).doesNotContain("Each stores the tokens it needs")
            .contains("While any of them is on, the server stores your TeamCity token, encrypted");
        assertThat(collapsed(Path.of("docs/features.ru.md")))
            .contains("Пока включена хоть одна, сервер хранит твой токен TeamCity зашифрованным");
    }

    @Test
    void allOffSaysWhatStillHoldsTheTokens() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const SETTINGS = { visa: false, rerun: true, gh: false, style: false, commands: false, login: null,
                jiraStored: false, ghStored: false, jiraTokenRejected: false, ghTokenRejected: false,
                tcTokenRejected: false, suites: false };
            page.route('/api/auto-visa-all', { body: SETTINGS }, 'GET');
            page.route('/api/auto-visa-all', { body: Object.assign({}, SETTINGS, { rerun: false }) }, 'POST');
            await page.load('?pr=13575');
            await page.el('settingsBtn').onclick();
            await page.settle();
            page.el('autoRerunToggle').checked = false;
            await page.el('autoRerunToggle').onchange();
            await page.settle();
            report({ msg: page.el('settingsMsg').textContent });
            """);

        assertThat(out.get("msg").asText()).isEqualTo("All off — the server no longer keeps tokens for your options; "
            + "an Auto visa you armed keeps your JIRA token until the visa is posted or you cancel it. Copies of the "
            + "tokens stay in the server's backups for 2 days, and your session cookie keeps the TeamCity and JIRA "
            + "tokens you pasted until you log out.");
    }

    @Test
    void theStoredTokensAreSaidToStayInTheBackups() throws IOException {
        assertThat(collapsed(Path.of("README.md"))).contains("Copies of these tokens stay in the backups of the "
            + "[cache directory](#the-cache-directory) for 2 days; backups older than 2 days keep the rest of the "
            + "state, without the tokens.");
        assertThat(collapsed(Path.of("README.ru.md"))).contains("Копии токенов остаются в бэкапах [каталога кэша]"
            + "(#каталог-кэша) 2 дня; в бэкапах старше 2 дней остаётся остальное состояние, без токенов.");
    }

    /** Without GITHUB_TOKEN there are no PR commands to poll for: the checker has no account to answer them from. */
    @Test
    void noTokenIsNotSixtyReadsAnHour() throws IOException {
        String off = "and the checker has no account to write with: the PR comment, PR commands, the checkstyle autofix"
            + " and tests to suites are off";
        assertThat(collapsed(Path.of("README.md"))).doesNotContain("read at most 60 times an hour").contains(off);
        assertThat(collapsed(Path.of("install.sh"))).doesNotContain("read at most 60 times").contains(off);
        assertThat(collapsed(Path.of("src/main/java/com/github/igniteprchecker/github/GithubClient.java")))
            .doesNotContain("reads GitHub at 60 requests an hour").contains("and the checker has no account to");
        assertThat(collapsed(Path.of("README.ru.md"))).doesNotContain("читается не чаще 60 раз в час")
            .contains("а писать чекеру не от кого");
    }

    @Test
    void backgroundReadsAreNotSaidToGoUnderTheCallersToken() throws IOException {
        assertThat(collapsed(Path.of("README.md"))).doesNotContain("always under the caller's token")
            .contains("background reads borrow other users' tokens and only read with them");
        assertThat(collapsed(Path.of("README.ru.md"))).doesNotContain("всегда под токеном вызывающего");
        assertThat(collapsed(Path.of("src/main/java/com/github/igniteprchecker/tc/TcClient.java")))
            .contains("background reads (warming, run states, the standing options' lookups) borrow other users' "
                + "tokens");
    }

    @Test
    void theSessionSecretIsSaidToEncryptTheStoredTokens() throws IOException {
        assertThat(collapsed(Path.of("src/main/resources/application.yml")))
            .contains("encrypts the session cookies and every token the service stores");
        assertThat(collapsed(Path.of("install.sh"))).doesNotContain("the session-cookie secret")
            .contains("SESSION_SECRET, which encrypts the session cookies and every token the service stores");
    }

    @Test
    void theTokenHintsAdviseAnExpiry() throws IOException {
        String jira = SettingsGroupsTest.group("groupJira").replaceAll("\\s+", " ");
        String gh = SettingsGroupsTest.group("groupGhToken").replaceAll("\\s+", " ");
        assertThat(jira).contains("that expires in 90 days: it has all the rights of your JIRA account.");
        assertThat(gh).contains("with Contents: Read and write, that expires in 90 days.");
        assertThat(collapsed(Path.of("README.md"))).contains("Give it and the GitHub token an expiry, say 90 days.");
        assertThat(collapsed(Path.of("README.ru.md"))).contains("стоит задать срок, например 90 дней");
    }

    /** The file's text with its whitespace collapsed and the comment marks that start its lines dropped. */
    /** A PR from a branch of apache/ignite gets the commits there, yet the docs said the token never writes there. */
    @Test
    void theUsersTokenIsSaidToCommitToApacheIgniteForABranchThere() throws IOException {
        assertThat(collapsed(Path.of("README.md"))).doesNotContain("never used to write in `apache/ignite`")
            .contains("It writes no comments, edits or reactions, and commits to `apache/ignite` only for a PR from a"
                + " branch of `apache/ignite` itself.");
        assertThat(collapsed(Path.of("docs/features.md"))).doesNotContain("it never writes in `apache/ignite`")
            .contains("it writes no comments, and it commits to `apache/ignite` only for a PR from a branch there.");
        assertThat(collapsed(Path.of("README.ru.md"))).doesNotContain("им не пишется ничего")
            .contains("в `apache/ignite` он коммитит, только если PR сделан из ветки самого `apache/ignite`.");
        assertThat(collapsed(Path.of("docs/features.ru.md"))).doesNotContain("им не пишется ничего")
            .contains("в `apache/ignite` коммитит, только если PR сделан из ветки там.");
    }

    private static String collapsed(Path file) throws IOException {
        return Files.readString(file, UTF_8).replaceAll("(?m)^\\s*(#|\\*|//) ?", "").replaceAll("\\s+", " ");
    }
}
