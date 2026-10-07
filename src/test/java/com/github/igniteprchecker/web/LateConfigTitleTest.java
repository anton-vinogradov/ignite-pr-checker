package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * An instance with its own JIRA: a PR opened from a link draws its title before /api/config answers, and the
 * IGNITE ticket in it must still link to that JIRA once the answer comes. A PR left for the start page before
 * the answer came must not come back as the title.
 */
class LateConfigTitleTest {
    /** Holds /api/config until {@code release()}; the instance's JIRA is jira.example. */
    private static final String HELD_CONFIG = """
        const realFetch = page.run('fetch');
        let release;
        const held = new Promise(resolve => { release = resolve; });
        page.run('window').fetch = async (url, opts) => {
            if (String(url).startsWith('/api/config')) await held;
            return realFetch(url, opts);
        };
        page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', githubRepo: 'apache/ignite',
            starCount: 1, refreshAfterSeconds: 120, jiraUrl: 'https://jira.example/' } });
        """;

    @Test
    void ticketLinkFollowsTheConfiguredJiraWhenItAnswersLate() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_CONFIG + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            const before = page.el('prTitle').innerHTML;
            release();
            await page.settle();
            report({ before, after: page.el('prTitle').innerHTML });
            """);

        assertThat(out.get("before").asText()).contains("href=\"https://issues.apache.org/jira/browse/IGNITE-29049\"");
        assertThat(out.get("after").asText()).contains("href=\"https://jira.example/browse/IGNITE-29049\"");
    }

    @Test
    void prLeftBeforeTheAnswerStaysClosed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD_CONFIG + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
            page.location.search = '';
            await page.popstate();
            release();
            await page.settle();
            report({ title: page.el('prTitle').textContent, selected: page.run('selectedPr') });
            """);

        assertThat(out.get("title").asText()).isEqualTo("Pick a PR on the left, or type its number in the filter.");
        assertThat(out.get("selected").isNull()).isTrue();
    }
}
