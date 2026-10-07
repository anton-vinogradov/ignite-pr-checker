package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import org.junit.jupiter.api.Test;

/**
 * "analysed just now" was computed once, so a tab left open for a day still said "just now", and the
 * run's date hid in a tooltip while the PR list held verdicts of three-week-old runs. A verdict served
 * stale is refreshed by the server in the background, but the open page never asked for the result.
 */
class FreshnessTest {
    @Test
    void timestampsKeepCountingWhileThePageIsOpen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const text = () => page.el('freshText').textContent || page.el('freshness').textContent;
            const before = text();
            await page.tick(2 * 3600 * 1000);
            report({ before, after: text() });
            """);

        assertThat(out.get("before").asText()).contains("run finished 7 Oct 11:00, 1h ago", "analysed 30s ago");
        assertThat(out.get("after").asText()).contains("run finished 7 Oct 11:00, 3h ago", "analysed 2h ago");
    }

    @Test
    void runOlderThanAWeekIsDatedAndFlagged() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ finishedAt: RUN_FINISHED + 3600 - 21 * 86400 }) });
            await page.load('?pr=13575');
            const old = page.el('freshText').innerHTML || page.el('freshness').innerHTML;
            page.route('/api/refresh', { body: verdict({ buildId: 9100, finishedAt: RUN_FINISHED + 3600 - 3 * 86400 }) }, 'POST');
            await page.run('refresh')();
            report({ old, recent: page.el('freshText').innerHTML });
            """);

        assertThat(out.get("old").asText()).contains("old-run").contains("run finished 16 Sep 12:00, 21d ago");
        assertThat(out.get("recent").asText()).doesNotContain("old-run").contains("run finished 4 Oct 12:00, 3d ago");
    }

    @Test
    void staleVerdictIsLookedAtOnceMoreQuietly() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() - 600000 }) });
            await page.load('?pr=13575');
            const first = page.fetches('/api/analyze').length;
            page.route('/api/analyze', () => ({ body: verdict({ computedAt: page.now() - 5000 }) }));
            await page.tick(30000);
            const looked = page.fetches('/api/analyze').length;
            const label = page.el('freshText').textContent;
            await page.tick(3600000);
            report({ first, looked, label, later: page.fetches('/api/analyze').length });
            """);

        assertThat(out.get("first").asInt()).isEqualTo(1);
        assertThat(out.get("looked").asInt()).isEqualTo(2);
        assertThat(out.get("label").asText()).contains("analysed").doesNotContain("10m ago");
        assertThat(out.get("later").asInt()).as("a fresh verdict is not polled for").isEqualTo(2);
    }

    @Test
    void staleVerdictOpenedAndLeftAtOnceIsFreshOnReturn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() - 3 * 3600000 }) });
            await page.load('?pr=13575');
            await page.setHidden(true);
            page.route('/api/analyze', () => ({ body: verdict({ computedAt: page.now() - 5000 }) }));
            await page.tick(30000);
            const whileHidden = page.fetches('/api/analyze').length;
            await page.tick(3600000);
            await page.setHidden(false);
            report({ whileHidden, analyses: page.fetches('/api/analyze').length, label: page.el('freshText').textContent });
            """);

        assertThat(out.get("whileHidden").asInt()).isEqualTo(2);
        assertThat(out.get("analyses").asInt()).isEqualTo(2);
        assertThat(out.get("label").asText()).contains("analysed 1h ago");
    }

    @Test
    void staleVerdictIsLookedAtOnceEvenWhenEveryVerdictIsStale() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', refreshAfterSeconds: 0 } });
            page.route('/api/analyze', () => ({ body: verdict({ computedAt: page.now() - 5000 }) }));
            await page.load('?pr=13575');
            await page.tick(600000);
            report({ analyses: page.fetches('/api/analyze').length });
            """);

        assertThat(out.get("analyses").asInt()).isEqualTo(2);
    }

    @Test
    void staleMeansOlderThanTheServersWindow() throws Exception {
        String scenario = """
            page.route('/api/config', { body: { teamcityUrl: 'https://ci2.example/', refreshAfterSeconds: 600 } });
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() - AGE * 1000 }) });
            await page.load('?pr=13575');
            await page.tick(30000);
            report({ analyses: page.fetches('/api/analyze').length });
            """;

        JsonNode within = PageScript.run("index.html", PageScript.SIGNED_IN + "const AGE = 300;" + scenario);
        JsonNode past = PageScript.run("index.html", PageScript.SIGNED_IN + "const AGE = 900;" + scenario);

        assertThat(within.get("analyses").asInt()).isEqualTo(1);
        assertThat(past.get("analyses").asInt()).isEqualTo(2);
    }

    @Test
    void pageLearnsTheServersRefreshWindow() {
        ConfigController config = new ConfigController(new TeamcityProperties("https://ci2.example/"),
            new GithubProperties(null, null, null), mock(GithubClient.class),
            new AnalysisProperties(null, "RunAll", null, null, null, 300, null), mock(SuiteBaseline.class),
            mock(JiraClient.class, RETURNS_MOCKS));

        assertThat(config.config()).containsEntry("refreshAfterSeconds", 300);
    }
}
