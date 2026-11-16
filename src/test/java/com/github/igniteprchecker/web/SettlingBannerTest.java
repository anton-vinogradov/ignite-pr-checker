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
            page.route('/api/runs', { body: [{ buildId: 9003, state: 'running', name: 'Cache' }] });
            page.route('/api/reruns', { body: [
                { pr: 13575, buildId: 9002, state: 'queued' },
                { pr: 13575, buildId: 9003, state: 'running' },
                { pr: 13575, buildId: 9003, state: 'running' },
                { pr: 13575, buildId: 9999, state: 'running' }] });
            await page.load('?pr=13575');
            const settling = { shown: !page.el('settlingRow').classList.contains('hidden'),
                text: page.el('settling').textContent, asked: page.fetches('/api/settling?pr=13575&build=9001').length };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            page.route('/api/runs', { body: [] });
            page.route('/api/reruns', { body: [] });
            await page.tick(90000);
            report({ settling, finalText: page.el('settling').textContent });
            """);

        assertThat(out.get("settling").get("shown").asBoolean()).isTrue();
        assertThat(out.get("settling").get("text").asText()).isEqualTo("Preliminary verdict — wait for the updated result."
            + " ♻︎ Auto re-run 2/2: 1 running, 1 queued. Wait for the updated verdict. Estimated time remaining: ~25m.");
        assertThat(out.get("settling").get("asked").asInt()).isPositive();
        assertThat(out.get("finalText").asText()).contains("Final verdict. Automatic re-runs are complete: 2 waves.")
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
            .isEqualTo("Preliminary verdict — wait for the updated result. The checker is processing the results and deciding"
                + " whether to re-run failed suites. Wait for its decision.");
    }

    /** Given a settled run with remaining failures, the page explicitly offers a manual next step. */
    @Test
    void aFinalVerdictSaysNoMoreAutoReRunsAreExpected() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 0 } });
            await page.load('?pr=13575');
            report({ shown: !page.el('settlingRow').classList.contains('hidden'), text: page.el('settling').textContent });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("text").asText()).isEqualTo("Final verdict. No automatic re-runs were started. Review the remaining"
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
            .startsWith("Preliminary verdict — wait").doesNotContain("Final verdict.");
        assertThat(out.get("after").asText()).startsWith("Final verdict.");
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

        assertThat(out.get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("Other runs of this PR: 0 running, 1 queued.", "The verdict below is for run 9001.")
            .doesNotContain("Run 9001 is queued", "Auto re-run 1/");
    }

    /**
     * Given receipts with known or missing history, when a finished verdict is displayed, then only known completed
     * waves are counted; an active wave stays preliminary even if its response includes a completed-wave count.
     */
    @Test
    void completedWaveHistoryNeverComesFromTheActiveWaveNumber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const texts = {};
            for (const [name, phase] of Object.entries({
                counted: { phase: 'final', wave: 0, of: 2, completedWaves: 2 },
                one: { phase: 'final', wave: 0, of: 2, completedWaves: 1 },
                none: { phase: 'final', wave: 0, of: 2, completedWaves: 0 },
                old: { phase: 'final', wave: 0, of: 2, reason: 'legacy' },
                active: { phase: 'settling', wave: 2, of: 2, completedWaves: 1, rerunBuildIds: [9003] }
            })) {
                page.route('/api/settling', { body: phase });
                await page.run('loadSettling')(13575);
                texts[name] = page.el('settling').textContent;
            }
            report(texts);
            """);

        assertThat(out.get("counted").asText()).startsWith("Final verdict. Automatic re-runs are complete: 2 waves.")
            .contains("Review the remaining failures");
        assertThat(out.get("one").asText()).startsWith("Final verdict. Automatic re-runs are complete: 1 wave.");
        assertThat(out.get("none").asText()).startsWith("Final verdict. No automatic re-runs were started.");
        assertThat(out.get("old").asText())
            .startsWith("Final verdict. The number of completed automatic re-run waves is unknown.")
            .doesNotContain("are complete:", "No automatic re-runs were started");
        assertThat(out.get("active").asText()).startsWith("Preliminary verdict — wait")
            .contains("Auto re-run 2/2").doesNotContain("Final verdict.", "are complete:");
    }

    /**
     * Given an old mixed verdict and a newer RunAll, when that run is active, then the old final receipt does not
     * make the page call the displayed verdict final.
     */
    @Test
    void aNewRunKeepsTheOldMixedVerdictPreliminary() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ buildId: 9499819, standing: 'UNPROVEN', revision: 'aaaaaaa',
                otherCodeRuns: [{ suite: 'Cache', suiteName: 'Cache', buildId: 9499820, revision: 'bbbbbbb' }] }) });
            page.route('/api/runs', { body: [{ buildId: 9502804, runAll: true, state: 'running', name: 'RunAll' },
                { buildId: 9502805, state: 'queued', name: 'Cache' }] });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent, title: page.run('document').title });
            """);

        assertThat(out.get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("Other runs of this PR: 1 running, 1 queued.", "The verdict below is for run 9499819.")
            .doesNotContain("Final verdict.", "are complete:");
        assertThat(out.get("title").asText()).startsWith("▶ running");
    }

    /**
     * Given a merged retained verdict, when unrelated post-merge work runs, then it neither reopens that verdict nor
     * replaces its completed-wave history; runs still due from before the merge do keep it preliminary.
     */
    @Test
    void postMergeWorkDoesNotReopenTheRetainedVerdict() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const merged = verdict({ mergedAt: page.now() / 1000 - 60, incompleteSince: page.now(), live: true });
            page.route('/api/analyze', { body: merged });
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'running' }] });
            page.route('/api/settling', { body: { phase: 'running', wave: 1, of: 2, completedWaves: 0 } });
            await page.load('?pr=13575');
            const retained = { text: page.el('settling').textContent, title: page.run('document').title };
            page.route('/api/runs', { status: 502, body: {} });
            await page.run('loadRuns')(13575);
            const unread = page.el('settling').textContent;
            page.route('/api/analyze', { body: Object.assign({}, merged, { runsAtMergeDue: true }) });
            await page.run('analyze')(13575, true);
            report({ retained, unread, pending: page.el('settling').textContent });
            """);

        assertThat(out.get("retained").get("text").asText()).startsWith("Final verdict.")
            .doesNotContain("Preliminary", "Other runs", "Auto re-run 1/2", "No automatic re-runs were started");
        assertThat(out.get("retained").get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
        assertThat(out.get("unread").asText()).isEqualTo(out.get("retained").get("text").asText());
        assertThat(out.get("pending").asText()).startsWith("Preliminary verdict — wait")
            .contains("Waiting for runs queued before the PR was merged").doesNotContain("Final verdict.");
    }

    /**
     * Given terminal receipts with cancellation or missing TeamCity data, when the verdict is displayed, then the
     * page names the limit without presenting the automatic re-run as a successful check of every failed suite.
     */
    @Test
    void terminalReasonsDoNotPromiseSuccessfulReRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const texts = {};
            for (const [reason, completedWaves] of [['cancelled', 1], ['gone', null], ['not-queued', 0],
                ['off', null], ['before-option', null], ['too-many-suites', 0]]) {
                page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, reason, completedWaves } });
                await page.run('loadSettling')(13575);
                texts[reason] = page.el('settling').textContent;
            }
            report(texts);
            """);

        assertThat(out.get("cancelled").asText()).contains("The run was cancelled", "only the available results");
        assertThat(out.get("gone").asText()).contains("completion could not be confirmed")
            .doesNotContain("are complete:", "No automatic re-runs were started");
        assertThat(out.get("not-queued").asText()).contains("could not queue", "re-run manually");
        assertThat(out.get("off").asText()).contains("Automatic re-runs are off")
            .doesNotContain("No automatic re-runs were started");
        assertThat(out.get("before-option").asText()).contains("not enabled when this run finished")
            .doesNotContain("No automatic re-runs were started");
        assertThat(out.get("too-many-suites").asText()).contains("checker skipped it", "re-run manually");
    }

    /**
     * Given a known systemic skip while the verdict is being processed, when the final receipt arrives, then the
     * page waits without inventing a wave or a pending re-run decision and finally reports zero completed waves.
     */
    @Test
    void aKnownSystemicSkipWaitsForTheVerdictWithoutPromisingAWave() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2,
                reason: 'too-many-suites', rerunBuildIds: [] } });
            await page.load('?pr=13575');
            const pending = page.el('settling').textContent;
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2,
                reason: 'too-many-suites', completedWaves: 0 } });
            await page.run('loadSettling')(13575);
            report({ pending, final: page.el('settling').textContent });
            """);

        assertThat(out.get("pending").asText()).isEqualTo("Preliminary verdict — wait for the updated result. Too many"
            + " suites needed an automatic re-run, so the checker skipped it. The runs finished. The checker is updating"
            + " the verdict.").doesNotContain("deciding", "Auto re-run 1/", "Auto re-run 2/");
        assertThat(out.get("final").asText()).startsWith("Final verdict. No automatic re-runs were started.")
            .contains("checker skipped it", "re-run manually").doesNotContain("Preliminary", "are complete:");
    }
}
