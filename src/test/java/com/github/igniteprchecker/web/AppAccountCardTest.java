package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Nothing told the operator whose GitHub account the checker writes as; on prod it was the owner's personal one,
 * which can push to apache/ignite. The status page names the account and what the check of its token found, and an
 * account GitHub said nothing of was shown green: now it is grey, saying what is unknown.
 */
class AppAccountCardTest {
    private static final String[] FUNCTIONS = {"card", "appAccountCard", "appAccountFound", "appAccountLevel"};

    private static final String FINE = """
        [{ level: 'ok', text: 'classic token, scopes: public_repo' }, { level: 'ok', text: 'does not expire' },
         { level: 'ok', text: 'cannot push to apache/ignite' },
         { level: 'ok', text: 'cannot push to anton-vinogradov/ignite-pr-checker' }]""";

    @Test
    void theAccountIsNamedWithWhatItsCheckFound() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker', canPush: false, checks: "
            + FINE + " }));").asText();

        assertThat(card).contains("App account", "@ignite-pr-checker", "class=\"v ok\"",
            "classic token, scopes: public_repo · does not expire · cannot push to apache/ignite");
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

        assertThat(cards.get(0).asText()).contains("no GITHUB_TOKEN");
        assertThat(cards.get(1).asText()).contains("GitHub refuses GITHUB_TOKEN", "class=\"v bad\"");
    }

    /** Runs {@code script} after the page's functions; whatever it passes to {@code out} comes back. */
    private static JsonNode run(String script) throws Exception {
        return PageFunctions.run("status.js", script, FUNCTIONS);
    }
}
