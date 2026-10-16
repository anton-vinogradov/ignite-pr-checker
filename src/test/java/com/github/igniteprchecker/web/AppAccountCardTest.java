package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Nothing told the operator whose GitHub account the checker writes as; on prod it was the owner's personal one,
 * which can push to apache/ignite. The status page names the account and what the check of its token found, and an
 * account GitHub said nothing of was shown green: now it is grey, saying what is unknown.
 */
class AppAccountCardTest {
    private static final String[] FUNCTIONS = {"card", "appAccountCard", "appAccountFound", "appAccountLevel",
        "appWritesCard"};

    private static final String FINE = """
        [{ level: 'ok', text: 'classic token, scopes: public_repo' }, { level: 'ok', text: 'does not expire' },
         { level: 'ok', text: 'cannot push to apache/ignite' },
         { level: 'ok', text: 'cannot push to anton-vinogradov/ignite-pr-checker' }]""";

    @Test
    void theAccountIsNamedWithWhatItsCheckFound() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker', canPush: false, checks: "
            + FINE + " }));").asText();

        assertThat(card).contains("App account", "@ignite-pr-checker", "class=\"v ok\"");
        assertThat(card.replaceAll("<[^>]+>", ""))
            .contains("classic token, scopes: public_repo · does not expire · cannot push to apache/ignite");
    }

    @Test
    void anAccountThatCanPushIsFlagged() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'anton-vinogradov', canPush: true, checks: ["
            + "{ level: 'warn', text: 'the app account can push to apache/ignite' }] }));").asText();

        assertThat(card).contains("@anton-vinogradov", "can push to apache/ignite", "class=\"v warn\"");
    }

    @Test
    void aTokenThatCannotWriteIsRed() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker', checks: ["
            + "{ level: 'error', text: 'GITHUB_TOKEN is a fine-grained token' },"
            + "{ level: 'warn', text: 'GITHUB_TOKEN expires on 2026-10-19, in 5 days' }] }));").asText();

        assertThat(card).contains("class=\"v bad\"");
    }

    @Test
    void whatGithubDidNotSayIsNotShownAsFine() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker', canPush: null, checks: ["
            + "{ level: 'ok', text: 'classic token, scopes: public_repo' },"
            + "{ level: 'unknown', text: 'GitHub did not say whether the app account can push to apache/ignite' }]"
            + " }));").asText();

        assertThat(card).contains("GitHub did not say whether the app account can push to apache/ignite",
            "class=\"v \"").doesNotContain("class=\"v ok\"");
    }

    /** Anonymous viewers get how bad, not what. */
    @Test
    void anonymousViewersAreToldToLogIn() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker', checks: ["
            + "{ level: 'ok', text: null }, { level: 'warn', text: null }] }));").asText();

        assertThat(card).contains("log in on the main page to see what its check found", "class=\"v warn\"");
    }

    @Test
    void noTokenAndARefusedOneAreSaid() throws Exception {
        JsonNode cards = run("out([appAccountCard({ state: 'none' }), appAccountCard({ state: 'refused' })]);");

        assertThat(cards.get(0).asText()).contains("no GITHUB_TOKEN: the PR comment, PR commands, the checkstyle"
            + " autofix and tests to suites are off");
        assertThat(cards.get(1).asText()).contains("GitHub refuses GITHUB_TOKEN", "class=\"v bad\"");
    }

    /** The account writes every user's comments: whether its writes wait or fail is the operator's to know. */
    @Test
    void writesThatWaitAreAmberAndARefusedOneRed() throws Exception {
        JsonNode cards = run("out([appWritesCard({ pausedUntil: 0, refusedAt: 0, capped: [] }),"
            + " appWritesCard({ pausedUntil: Date.now() + 60000, refusedAt: 0, capped: ['PR 13800'] }),"
            + " appWritesCard({ pausedUntil: 0, refusedAt: 1, refused: 'GitHub answered 403 to a write: blocked',"
            + " capped: [] }), appWritesCard({ pausedUntil: 0, refusedAt: 1, refused: null, capped: [] })]);");

        assertThat(cards.get(0).asText()).contains("App writes", "none refused or waiting", "class=\"v ok\"");
        assertThat(cards.get(1).asText()).contains("GitHub rate-limits them until", "hourly cap reached for PR 13800",
            "class=\"v warn\"");
        assertThat(cards.get(2).asText()).contains("GitHub answered 403 to a write: blocked", "class=\"v bad\"");
        assertThat(cards.get(3).asText()).contains("log in to see what it said");
    }

    /** The calls by token said users' tokens post the comments, edits and reactions, which the app account does. */
    @Test
    void theCallsOfEachTokenAreNamedAsTheyAre() throws IOException {
        String page = Files.readString(Path.of("src/main/resources/static/status.html"), StandardCharsets.UTF_8);

        assertThat(page).contains("App (server) token · last hour <span class=\"muted\">· PRs, files, checks, comment"
            + " poll; every comment, edit &amp; reaction</span>", "Users' own PATs · last hour <span class=\"muted\">·"
            + " token checks, GitHub account links, fix commits</span>");
    }

    /** Runs {@code script} after the page's functions; whatever it passes to {@code out} comes back. */
    private static JsonNode run(String script) throws Exception {
        return PageFunctions.run("status.js", script, FUNCTIONS);
    }
}
