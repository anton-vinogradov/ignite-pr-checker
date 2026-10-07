package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Nothing told the operator whose GitHub account the checker writes as; on prod it was the owner's personal one,
 * which can push to apache/ignite. The status page names the account and flags one with write access.
 */
class AppAccountCardTest {
    private static final String[] FUNCTIONS = {"card", "appAccountCard"};

    @Test
    void theAccountIsNamed() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'ignite-pr-checker-bot', canPush: false }));")
            .asText();

        assertThat(card).contains("App account", "@ignite-pr-checker-bot", "class=\"v ok\"");
    }

    @Test
    void anAccountThatCanPushIsFlagged() throws Exception {
        String card = run("out(appAccountCard({ state: 'ok', login: 'anton-vinogradov', canPush: true }));").asText();

        assertThat(card).contains("@anton-vinogradov", "can push to the repo", "class=\"v warn\"");
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
