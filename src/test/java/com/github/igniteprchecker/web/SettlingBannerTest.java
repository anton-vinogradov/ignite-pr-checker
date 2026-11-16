package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The result names work still in progress and says when the checker expects no more auto re-runs of that run. */
class SettlingBannerTest {
    /** Given a wave's builds, its live counts exclude other builds and disappear when that run is settled. */
    @Test
    void aVerdictReRunsStillSettleSaysItIsInterim() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 2, of: 2, what: '3 suites with blockers',
                rerunBuildIds: [9002, 9003], etaEpochSec: Math.floor(page.now() / 1000) + 25 * 60 } });
            page.route('/api/reruns', { body: [
                { pr: 13575, buildId: 9002, state: 'queued' },
                { pr: 13575, buildId: 9003, state: 'running' },
                { pr: 13575, buildId: 9003, state: 'running' },
                { pr: 13575, buildId: 9999, state: 'running' }] });
            await page.load('?pr=13575');
            const settling = { shown: !page.el('settlingRow').classList.contains('hidden'),
                text: page.el('settling').textContent, asked: page.fetches('/api/settling?pr=13575&build=9001').length };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            page.route('/api/reruns', { body: [] });
            await page.tick(90000);
            report({ settling, finalText: page.el('settling').textContent });
            """);

        assertThat(out.get("settling").get("shown").asBoolean()).isTrue();
        assertThat(out.get("settling").get("text").asText()).isEqualTo("♻︎ Auto re-run 2/2: 1 running, 1 queued. Wait for the"
            + " updated verdict. Estimated time remaining: ~25m.");
        assertThat(out.get("settling").get("asked").asInt()).isPositive();
        assertThat(out.get("finalText").asText()).contains("Auto re-runs of this run are no longer expected.")
            .doesNotContain("re-run 2/2");
    }

    /** Given a pending decision, the status asks the reader to wait without promising a build was queued. */
    @Test
    void theDecisionOnReRunsIsSaidToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2 } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent });
            """);

        assertThat(out.get("text").asText())
            .isEqualTo("The checker is processing the results and deciding whether to re-run failed suites. Wait for its decision.");
    }

    /** Given a settled run with remaining failures, the page explicitly offers a manual next step. */
    @Test
    void aFinalVerdictSaysNoMoreAutoReRunsAreExpected() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.load('?pr=13575');
            report({ shown: !page.el('settlingRow').classList.contains('hidden'), text: page.el('settling').textContent });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("text").asText()).isEqualTo("Auto re-runs of this run are no longer expected. Review the remaining"
            + " failures; fix their cause or re-run manually.");
    }

    /** Given TeamCity errors, the existing twenty-minute analysis hold stays visible until its own deadline. */
    @Test
    void anAnalysisRetryDoesNotLookIdleBeforeItsDeadline() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ incompleteSince: page.now() - 19 * 60000 }) });
            await page.load('?pr=13575');
            const waiting = page.el('settling').textContent;
            await page.tick(61000);
            report({ waiting, after: page.el('settling').textContent });
            """);

        assertThat(out.get("waiting").asText()).contains("TeamCity errors", "retrying", "~1m")
            .doesNotContain("no longer expected");
        assertThat(out.get("after").asText()).contains("Auto re-runs of this run are no longer expected.");
    }

    /** Given an early auto re-run, the still-running chain and queued repeat are both named. */
    @Test
    void anEarlyReRunRemainsVisibleWhileTheChainRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9001, finishedAt: 0 }) });
            page.route('/api/runs', { body: [{ buildId: 9001, state: 'running', runAll: true }] });
            page.route('/api/reruns', { body: [{ pr: 13575, buildId: 9002, state: 'queued' }] });
            page.route('/api/settling', { body: { phase: 'running', wave: 1, of: 2, rerunBuildIds: [9002] } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent });
            """);

        assertThat(out.get("text").asText()).contains("Run 9001 is running on TeamCity.",
            "Auto re-run 1/2: 0 running, 1 queued.");
    }

    /** Given a newer queued run, it is described as another run of the PR, not the analysed build. */
    @Test
    void anotherPrRunIsNotCalledTheRunOfThisVerdict() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/runs', { body: [{ buildId: 9100, state: 'queued', runAll: true }] });
            page.route('/api/settling', { body: { phase: 'running', wave: 0, of: 2 } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent });
            """);

        assertThat(out.get("text").asText()).contains("Other runs of this PR: 0 running, 1 queued.",
            "The verdict below is for run 9001.").doesNotContain("Run 9001 is queued", "Auto re-run 1/");
    }
}
