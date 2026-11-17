package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The result names work still in progress and says when the checker expects no more auto re-runs of that run. The
 * status line says whether the verdict is final and what the steps cannot say; the step of auto re-runs says the rest.
 */
class SettlingBannerTest {
    /** The step of auto re-runs: its state and what it says. */
    private static final String AUTO_STEP = """
        function autoStep() {
            const li = page.el('steps').innerHTML.split('</li>')[2];
            return li.match(/class="step (\\w+)"/)[1] + ' ' + li.match(/class="step-detail">([^<]*)/)[1];
        }
        """;

    /** Given a wave's builds, its live counts exclude other builds and disappear when that run is settled. */
    @Test
    void aVerdictReRunsStillSettleSaysItIsInterim() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + AUTO_STEP + """
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
                text: page.el('settling').textContent, step: autoStep(),
                asked: page.fetches('/api/settling?pr=13575&build=9001').length };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            page.route('/api/runs', { body: [] });
            page.route('/api/reruns', { body: [] });
            await page.tick(90000);
            report({ settling, finalText: page.el('settling').textContent, finalStep: autoStep() });
            """);

        assertThat(out.get("settling").get("shown").asBoolean()).isTrue();
        assertThat(out.get("settling").get("text").asText())
            .isEqualTo("Preliminary verdict — wait for the updated result.");
        assertThat(out.get("settling").get("step").asText())
            .isEqualTo("now wave 2 of 2 · 1 running, 1 queued · ~25m left");
        assertThat(out.get("settling").get("asked").asInt()).isPositive();
        assertThat(out.get("finalText").asText()).startsWith("Final verdict.");
        assertThat(out.get("finalStep").asText()).isEqualTo("done 2 waves");
    }

    /** Given a pending decision, the status asks the reader to wait without promising a build was queued. */
    @Test
    void theDecisionOnReRunsIsSaidToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + AUTO_STEP + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2 } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent, step: autoStep() });
            """);

        assertThat(out.get("text").asText()).isEqualTo("Preliminary verdict — wait for the updated result.");
        assertThat(out.get("step").asText()).isEqualTo("now deciding");
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
        assertThat(out.get("text").asText())
            .isEqualTo("Final verdict. Review the remaining failures; fix their cause or re-run manually.");
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
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + AUTO_STEP + """
            page.route('/api/analyze', { body: verdict({ live: true, liveBuildId: 9001, finishedAt: 0 }) });
            page.route('/api/runs', { body: [{ buildId: 9001, state: 'running', runAll: true }] });
            page.route('/api/reruns', { body: [{ pr: 13575, buildId: 9002, state: 'queued' }] });
            page.route('/api/settling', { body: { phase: 'running', wave: 1, of: 2, rerunBuildIds: [9002] } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent, steps: page.el('steps').innerHTML, step: autoStep() });
            """);

        assertThat(out.get("text").asText()).isEqualTo("Preliminary verdict — wait for the updated result.");
        assertThat(out.get("steps").asText()).contains("<span class=\"step-name\">RunAll<i ");
        assertThat(out.get("step").asText()).isEqualTo("now wave 1 of 2 · 0 running, 1 queued");
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
            .contains("Other runs of this PR: 0 running, 1 queued.", "This verdict is for run 9001.")
            .doesNotContain("Run 9001 is queued", "Auto re-run 1/");
    }

    /**
     * Given receipts with known or missing history, when a finished verdict is displayed, then the step counts only
     * known completed waves; an active wave stays preliminary even if its response includes a completed-wave count.
     */
    @Test
    void completedWaveHistoryNeverComesFromTheActiveWaveNumber() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + AUTO_STEP + """
            await page.load('?pr=13575');
            const texts = {}, steps = {};
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
                steps[name] = autoStep();
            }
            report({ texts, steps });
            """);

        JsonNode texts = out.get("texts");
        JsonNode steps = out.get("steps");
        for (String settled : new String[] {"counted", "one", "none", "old"})
            assertThat(texts.get(settled).asText()).as(settled)
                .isEqualTo("Final verdict. Review the remaining failures; fix their cause or re-run manually.");
        assertThat(steps.get("counted").asText()).isEqualTo("done 2 waves");
        assertThat(steps.get("one").asText()).isEqualTo("done 1 wave");
        assertThat(steps.get("none").asText()).isEqualTo("done none started");
        assertThat(steps.get("old").asText()).as("unknown history is not zero").isEqualTo("done ");
        assertThat(texts.get("active").asText()).isEqualTo("Preliminary verdict — wait for the updated result.");
        assertThat(steps.get("active").asText()).isEqualTo("now wave 2 of 2");
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
            .contains("Other runs of this PR: 1 running, 1 queued.", "This verdict is for run 9499819.")
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
     * page names the limit without presenting the automatic re-run as a successful check of every failed suite: in
     * the skipped step when that says why, in the status line otherwise.
     */
    @Test
    void terminalReasonsDoNotPromiseSuccessfulReRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + AUTO_STEP + """
            await page.load('?pr=13575');
            const texts = {}, steps = {};
            for (const [reason, completedWaves] of [['cancelled', 1], ['gone', null], ['not-queued', 0],
                ['off', null], ['before-option', null], ['too-many-suites', 0]]) {
                page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, reason, completedWaves } });
                await page.run('loadSettling')(13575);
                texts[reason] = page.el('settling').textContent;
                steps[reason] = autoStep();
            }
            report({ texts, steps });
            """);

        JsonNode texts = out.get("texts");
        JsonNode steps = out.get("steps");
        assertThat(texts.get("cancelled").asText()).contains("The run was cancelled", "only the available results");
        assertThat(steps.get("cancelled").asText()).isEqualTo("done 1 wave");
        assertThat(texts.get("gone").asText()).contains("completion could not be confirmed");
        assertThat(steps.get("gone").asText()).as("no count for a re-run that is gone").isEqualTo("done ");
        assertThat(steps.get("not-queued").asText()).isEqualTo("off could not be queued");
        assertThat(steps.get("off").asText()).isEqualTo("off off for this run");
        assertThat(steps.get("before-option").asText()).isEqualTo("off off for this run");
        assertThat(steps.get("too-many-suites").asText()).isEqualTo("off skipped: too many suites");
        for (String skipped : new String[] {"not-queued", "off", "before-option", "too-many-suites"})
            assertThat(texts.get(skipped).asText()).as(skipped)
                .isEqualTo("Final verdict. Review the remaining failures; fix their cause or re-run manually.");
    }

    /** Given a merged PR, which has no steps, the status line itself counts the waves and says why none went. */
    @Test
    void withoutStepsTheLineSaysWhyNoReRunWent() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ mergedAt: page.now() / 1000 - 60 }) });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, reason: 'too-many-suites',
                completedWaves: 0 } });
            await page.load('?pr=13575');
            report({ steps: !page.el('steps').classList.contains('hidden'), text: page.el('settling').textContent });
            """);

        assertThat(out.get("steps").asBoolean()).isFalse();
        assertThat(out.get("text").asText()).isEqualTo("Final verdict. No automatic re-runs were started. Too many"
            + " suites needed an automatic re-run, so the checker skipped it.");
    }

    /**
     * Given a known systemic skip while the verdict is being processed, when the final receipt arrives, then the
     * page waits without inventing a wave or a pending re-run decision: the step says the re-run was skipped.
     */
    @Test
    void aKnownSystemicSkipWaitsForTheVerdictWithoutPromisingAWave() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + AUTO_STEP + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2,
                reason: 'too-many-suites', rerunBuildIds: [] } });
            await page.load('?pr=13575');
            const pending = { text: page.el('settling').textContent, step: autoStep(),
                steps: page.el('steps').innerHTML };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2,
                reason: 'too-many-suites', completedWaves: 0 } });
            await page.run('loadSettling')(13575);
            report({ pending, final: page.el('settling').textContent, finalStep: autoStep() });
            """);

        assertThat(out.at("/pending/text").asText()).isEqualTo("Preliminary verdict — wait for the updated result."
            + " The runs finished. The checker is updating the verdict.");
        assertThat(out.at("/pending/step").asText()).isEqualTo("off skipped: too many suites");
        assertThat(out.at("/pending/steps").asText()).doesNotContain("deciding", "wave ");
        assertThat(out.get("final").asText())
            .isEqualTo("Final verdict. Review the remaining failures; fix their cause or re-run manually.");
        assertThat(out.get("finalStep").asText()).isEqualTo("off skipped: too many suites");
    }
}
