package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * PR 13335's page said "No blockers 🎉 … good to go" and its tab "✅ no blockers" from the cached analysis of build
 * 9398719, and only 1.6 s later, once /api/pending answered, that 33 commits were pushed since. Had /api/pending
 * failed, "good to go" would have stayed. A clean verdict now waits for the check that its run tested the PR's head,
 * says when the check failed, and says at once what the server's PR list already knows.
 */
class HeadCheckPageTest {
    /** Holds /api/pending's answer back until the scenario calls {@code answerPending()}. */
    private static final String HELD = """
        const g = page.run('this');
        const fetchNow = g.fetch;
        let answerPending;
        let pendingFails = false;
        const pendingAnswered = new Promise(r => { answerPending = r; });
        g.fetch = async (url, opts) => {
            if (String(url).startsWith('/api/pending')) {
                await pendingAnswered;
                if (pendingFails) throw new TypeError('Failed to fetch');
            }
            return fetchNow(url, opts);
        };
        const state = () => ({ head: page.el('blockersHead').textContent,
            clean: page.el('blockersTitle').classList.contains('clean'),
            caveat: page.el('verdictCaveat').classList.contains('hidden') ? null : page.el('verdictCaveat').textContent,
            below: page.el('noBlockers').textContent,
            pushed: page.el('pendingRow').classList.contains('hidden') ? null : page.el('pending').textContent,
            title: page.run('document').title });
        const CLEAN = verdict({ blockers: [], standing: 'CLEAN', caveats: [],
            revision: '1f93640c2b7a9e5d4f3a1b0c9d8e7f6a5b4c3d2e' });
        const AHEAD = { pending: true, ahead: 33, builtSha: '1f93640', headSha: '6573622', rewritten: false,
            builtRevision: '1f93640c2b7a9e5d4f3a1b0c9d8e7f6a5b4c3d2e',
            caveat: '33 commit(s) pushed since this run — it tested older code' };
        """;

    private static final String CANT_PROVE =
        "Nothing here was blamed on this PR, but this run can't prove it is clean: ";

    @Test
    void aCleanVerdictWaitsForTheHeadCheck() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: CLEAN });
            page.route('/api/pending', { body: { pending: false, checked: true } });
            await page.load('?pr=13575');
            const before = state();
            answerPending();
            await page.settle();
            report({ before, after: state() });
            """);

        JsonNode before = out.get("before");
        assertThat(before.get("head").asText()).isEqualTo("No test blockers");
        assertThat(before.get("clean").asBoolean()).isFalse();
        assertThat(before.get("caveat").asText()).isEqualTo("Checking that this run tested the PR's latest code…");
        assertThat(before.get("below").asText()).doesNotContain("good to go");
        assertThat(before.get("title").asText()).isEqualTo("#13575 — Ignite PR Checker");

        JsonNode after = out.get("after");
        assertThat(after.get("head").asText()).isEqualTo("No blockers 🎉");
        assertThat(after.get("clean").asBoolean()).isTrue();
        assertThat(after.get("caveat").isNull()).isTrue();
        assertThat(after.get("below").asText()).isEqualTo("Nothing in this run looks caused by your PR — good to go.");
        assertThat(after.get("title").asText()).isEqualTo("✅ no blockers · #13575 — Ignite PR Checker");
    }

    @Test
    void commitsPushedSinceMakeItOldCodeInTheTabToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: CLEAN });
            page.route('/api/pending', { body: AHEAD });
            await page.load('?pr=13575');
            answerPending();
            await page.settle();
            report(state());
            """);

        assertThat(out.get("head").asText()).isEqualTo("No test blockers");
        assertThat(out.get("caveat").asText())
            .isEqualTo(CANT_PROVE + "33 commit(s) pushed since this run — it tested older code.");
        assertThat(out.get("below").asText()).doesNotContain("good to go");
        assertThat(out.get("pushed").asText()).isEqualTo("⚠ 33 new commits pushed since this run (1f93640 → 6573622) — "
            + "the verdict is for the older code.");
        assertThat(out.get("title").asText()).startsWith("⚠ no test blockers");
    }

    /** GitHub or TeamCity could not say, /api/pending failed, or the request never got through. */
    @Test
    void aFailedCheckIsSaid() throws Exception {
        for (String failure : new String[] {
            "page.route('/api/pending', { body: { pending: false, checked: false } });",
            "page.route('/api/pending', { status: 502, body: { error: 'Bad Gateway' } });",
            "pendingFails = true;"}) {
            JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
                page.route('/api/analyze', { body: CLEAN });
                """ + failure + """
                await page.load('?pr=13575');
                answerPending();
                await page.settle();
                report(state());
                """);

            assertThat(out.get("head").asText()).as(failure).isEqualTo("No test blockers");
            assertThat(out.get("clean").asBoolean()).as(failure).isFalse();
            assertThat(out.get("caveat").asText()).as(failure)
                .isEqualTo(CANT_PROVE + "could not check whether the PR changed since this run.");
            assertThat(out.get("below").asText()).as(failure).doesNotContain("good to go");
            assertThat(out.get("pushed").isNull()).as(failure).isTrue();
            assertThat(out.get("title").asText()).as(failure).startsWith("⚠ no test blockers");
        }
    }

    /** The server's PR list had seen the new head of PR 13335: the first draw already says old code. */
    @Test
    void anAnalysisThatKnowsTheHeadMovedSaysOldCodeAtOnce() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { pushed: { pending: true, ahead: -1,
                builtSha: '1f93640', headSha: '6573622', rewritten: false, builtRevision: CLEAN.revision,
                caveat: 'new commits pushed since this run — it tested older code' } }) });
            page.route('/api/pending', { body: AHEAD });
            await page.load('?pr=13575');
            const first = state();
            answerPending();
            await page.settle();
            report({ first, counted: state() });
            """);

        JsonNode first = out.get("first");
        assertThat(first.get("head").asText()).isEqualTo("No test blockers");
        assertThat(first.get("caveat").asText())
            .isEqualTo(CANT_PROVE + "new commits pushed since this run — it tested older code.");
        assertThat(first.get("pushed").asText()).isEqualTo("⚠ new commits pushed since this run (1f93640 → 6573622) — "
            + "the verdict is for the older code.");
        assertThat(first.get("title").asText()).startsWith("⚠ no test blockers");

        JsonNode counted = out.get("counted");
        assertThat(counted.get("caveat").asText())
            .isEqualTo(CANT_PROVE + "33 commit(s) pushed since this run — it tested older code.");
        assertThat(counted.get("pushed").asText()).startsWith("⚠ 33 new commits pushed since this run");
    }

    /** A failed check does not undo commits the PR list already showed. */
    @Test
    void aFailedCheckKeepsTheCommitsTheListShowed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { pushed: { pending: true, ahead: -1,
                builtSha: '1f93640', headSha: '6573622', rewritten: false, builtRevision: CLEAN.revision,
                caveat: 'new commits pushed since this run — it tested older code' } }) });
            pendingFails = true;
            await page.load('?pr=13575');
            answerPending();
            await page.settle();
            report(state());
            """);

        assertThat(out.get("caveat").asText())
            .isEqualTo(CANT_PROVE + "new commits pushed since this run — it tested older code.");
        assertThat(out.get("pushed").asText()).startsWith("⚠ new commits pushed since this run");
    }

    /** Blockers need no head check to be told: they show, in the tab too, as before. */
    @Test
    void blockersShowWithoutWaiting() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: verdict({ standing: 'BLOCKERS', caveats: [] }) });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("head").asText()).isEqualTo("Blockers");
        assertThat(out.get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
    }

    /**
     * The PR list loaded with a ✓ for the open PR from a head it had fetched before the latest push, or with a "?"
     * from a head older than the run's: once the page knows, the list badges the PR as the page heads it. A badge for
     * anything but the head, such as a newer run still going, stays the list's.
     */
    @Test
    void theListBadgesTheOpenPrAsThePageHeadsIt() throws Exception {
        String scenario = """
            const li = page.el('li-13575');
            Object.assign(li.dataset, { num: '13575', title: 'IGNITE-29049 Move MDC to JUnit', url: 'u' });
            const badge = page.el('badge-13575');
            li.querySelector = sel => (sel === '.pr-badge' ? badge : null);
            page.el('prList').children = [li];
            page.route('/api/prs', { body: [{ number: 13575, title: 'IGNITE-29049 Move MDC to JUnit', url: 'u',
                triggeredBy: 'alice', blockers: 0, proven: LISTED === 'CLEAN', standing: LISTED }] });
            page.route('/api/analyze', { body: ANALYSED });
            await page.load('?pr=13575');
            const before = badge.outerHTML || null;
            answerPending();
            await page.settle();
            report({ before, after: badge.outerHTML || null });
            """;

        JsonNode old = PageScript.run("index.html", PageScript.SIGNED_IN + HELD
            + "const LISTED = 'CLEAN', ANALYSED = CLEAN; page.route('/api/pending', { body: AHEAD });" + scenario);
        JsonNode current = PageScript.run("index.html", PageScript.SIGNED_IN + HELD
            + "const LISTED = 'OLD_CODE', ANALYSED = CLEAN;"
            + "page.route('/api/pending', { body: { pending: false, checked: true } });" + scenario);
        JsonNode live = PageScript.run("index.html", PageScript.SIGNED_IN + HELD
            + "const LISTED = 'CLEAN', ANALYSED = verdict({ blockers: [], standing: 'UNPROVEN', live: true,"
            + "caveats: ['a newer run is still going — its unfinished suites can still fail'] });" + scenario);

        assertThat(old.get("before").isNull()).as("nothing is known while the check runs").isTrue();
        assertThat(old.get("after").asText()).contains("pr-badge unproven", ">?<", "old code");
        assertThat(current.get("before").isNull()).isTrue();
        assertThat(current.get("after").asText()).contains("pr-badge ok", ">✓<");
        assertThat(live.get("after").isNull()).isTrue();
    }
}
