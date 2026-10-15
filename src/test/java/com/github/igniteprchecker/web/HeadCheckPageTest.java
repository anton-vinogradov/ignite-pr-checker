package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PR 13335's page said "No blockers … good to go" and its tab "✅ no blockers" from the cached analysis of build
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
        const state = () => ({ head: page.el('verdictText').textContent,
            clean: page.el('verdictCard').classList.contains('clean'),
            lines: page.el('verdictLines').textContent,
            pushed: page.el('pendingRow').classList.contains('hidden') ? null : page.el('pending').textContent,
            title: page.run('document').title });
        const CLEAN = verdict({ blockers: [], standing: 'CLEAN', caveats: [],
            revision: '1f93640c2b7a9e5d4f3a1b0c9d8e7f6a5b4c3d2e' });
        const AHEAD = { pending: true, ahead: 33, builtSha: '1f93640', headSha: '6573622', rewritten: false,
            builtRevision: '1f93640c2b7a9e5d4f3a1b0c9d8e7f6a5b4c3d2e',
            caveat: '33 commit(s) pushed since this run — it tested older code' };
        const LIST_MOVED = { pending: true, ahead: -1, builtSha: '1f93640', headSha: '6573622', rewritten: false,
            builtRevision: CLEAN.revision, caveat: 'new commits pushed since this run — it tested older code' };
        """;

    private static final String CANT_PROVE = "This run can't prove the PR is clean: ";

    private static final String OLDER_CODE = "it tested older code (see the note at the top).";

    /**
     * {@code listed([[number, standing], …])} serves the PR list with these PRs, none with blockers, and returns, by
     * PR, every badge the page draws in its place after the list's own.
     */
    private static final String LIST = """
        function listed(prs) {
            const badges = {};
            page.el('prList').children = prs.map(([num]) => {
                const li = page.el('li-' + num);
                Object.assign(li.dataset, { num: String(num), title: 'PR ' + num, url: 'u' });
                const drawn = badges[num] = [];
                const badge = {};
                Object.defineProperty(badge, 'outerHTML', { set: html => drawn.push(html) });
                li.querySelector = sel => (sel === '.pr-badge' ? badge : null);
                return li;
            });
            page.route('/api/prs', { body: prs.map(([num, standing]) => ({ number: num, title: 'PR ' + num, url: 'u',
                triggeredBy: 'alice', blockers: 0, proven: standing === 'CLEAN', standing })) });
            return badges;
        }
        """;

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
        assertThat(before.get("head").asText()).isEqualTo("No blockers found");
        assertThat(before.get("clean").asBoolean()).isFalse();
        assertThat(before.get("lines").asText()).isEqualTo("Checking that this run tested the PR's latest commit…");
        assertThat(before.get("title").asText()).isEqualTo("#13575 — Ignite PR Checker");

        JsonNode after = out.get("after");
        assertThat(after.get("head").asText()).isEqualTo("✓ No blockers");
        assertThat(after.get("clean").asBoolean()).isTrue();
        assertThat(after.get("lines").asText())
            .isEqualTo("Nothing in this run looks caused by your PR. You don't need to fix or rerun anything.");
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

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("lines").asText()).isEqualTo(CANT_PROVE + OLDER_CODE);
        assertThat(out.get("pushed").asText()).isEqualTo("⚠ The verdict is for older code: this run tested 1f93640, and "
            + "the PR is now at 6573622, 33 commits later.");
        assertThat(out.get("title").asText()).startsWith("⚠ not proven clean");
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

            assertThat(out.get("head").asText()).as(failure).isEqualTo("No blockers found — not proven clean");
            assertThat(out.get("clean").asBoolean()).as(failure).isFalse();
            assertThat(out.get("lines").asText()).as(failure)
                .isEqualTo(CANT_PROVE + "could not check whether the PR changed since this run.");
            assertThat(out.get("pushed").isNull()).as(failure).isTrue();
            assertThat(out.get("title").asText()).as(failure).startsWith("⚠ not proven clean");
        }
    }

    /** The server's PR list had seen the new head of PR 13335: the first draw already says old code. */
    @Test
    void anAnalysisThatKnowsTheHeadMovedSaysOldCodeAtOnce() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { pushed: LIST_MOVED }) });
            page.route('/api/pending', { body: AHEAD });
            await page.load('?pr=13575');
            const first = state();
            answerPending();
            await page.settle();
            report({ first, counted: state() });
            """);

        JsonNode first = out.get("first");
        assertThat(first.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(first.get("lines").asText()).isEqualTo(CANT_PROVE + OLDER_CODE);
        assertThat(first.get("pushed").asText()).as("the count is not known yet")
            .isEqualTo("⚠ The verdict is for older code: this run tested 1f93640, and the PR is now at 6573622.");
        assertThat(first.get("title").asText()).startsWith("⚠ not proven clean");

        JsonNode counted = out.get("counted");
        assertThat(counted.get("lines").asText()).isEqualTo(CANT_PROVE + OLDER_CODE);
        assertThat(counted.get("pushed").asText()).endsWith("the PR is now at 6573622, 33 commits later.");
    }

    /** A failed check does not undo commits the PR list already showed. */
    @Test
    void aFailedCheckKeepsTheCommitsTheListShowed() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { pushed: LIST_MOVED }) });
            pendingFails = true;
            await page.load('?pr=13575');
            answerPending();
            await page.settle();
            report(state());
            """);

        assertThat(out.get("lines").asText()).isEqualTo(CANT_PROVE + OLDER_CODE);
        assertThat(out.get("pushed").asText()).isEqualTo("⚠ The verdict is for older code: this run tested 1f93640, and "
            + "the PR is now at 6573622.");
    }

    /** A quiet look again that brings the same run with the list's uncounted commits keeps GitHub's count. */
    @Test
    void aRedrawOfTheSameRunKeepsTheCount() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { pushed: LIST_MOVED }) });
            page.route('/api/pending', { body: AHEAD });
            await page.load('?pr=13575');
            answerPending();
            await page.settle();
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { pushed: LIST_MOVED, suitesRan: 11 }) });
            await page.run('analyze')(13575, true);
            report(state());
            """);

        assertThat(out.get("lines").asText()).isEqualTo(CANT_PROVE + OLDER_CODE);
        assertThat(out.get("pushed").asText()).endsWith("the PR is now at 6573622, 33 commits later.");
    }

    /**
     * GitHub answered for build 9001 only after a newer run, 9002, was on the page: the answer is about code the page
     * no longer shows, so it says nothing of 9002.
     */
    @Test
    void anAnswerForARunNoLongerShownChangesNothing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            let answerOld, answerNew;
            const oldAnswered = new Promise(r => { answerOld = r; });
            const newAnswered = new Promise(r => { answerNew = r; });
            const heldFetch = g.fetch;
            g.fetch = async (url, opts) => {
                const u = String(url);
                if (u.startsWith('/api/pending')) await (u.includes('build=9001') ? oldAnswered : newAnswered);
                return heldFetch(url, opts);
            };
            answerPending();
            page.route('/api/analyze', { body: CLEAN });
            page.route('/api/pending', url => ({ body: url.includes('build=9001') ? AHEAD
                : { pending: false, checked: true } }));
            await page.load('?pr=13575');
            page.route('/api/analyze', { body: Object.assign({}, CLEAN, { buildId: 9002 }) });
            await page.run('analyze')(13575, true);
            answerOld();
            await page.settle();
            const stale = state();
            answerNew();
            await page.settle();
            report({ stale, current: state() });
            """);

        JsonNode stale = out.get("stale");
        assertThat(stale.get("head").asText()).isEqualTo("No blockers found");
        assertThat(stale.get("lines").asText()).isEqualTo("Checking that this run tested the PR's latest commit…");
        assertThat(stale.get("pushed").isNull()).isTrue();

        JsonNode current = out.get("current");
        assertThat(current.get("head").asText()).isEqualTo("✓ No blockers");
        assertThat(current.get("pushed").isNull()).isTrue();
    }

    /** Blockers need no head check to be told: they show, in the tab too, as before. */
    @Test
    void blockersShowWithoutWaiting() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + """
            page.route('/api/analyze', { body: verdict({ standing: 'BLOCKERS', caveats: [] }) });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("head").asText()).isEqualTo("1 blocker in 1 suite");
        assertThat(out.get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
    }

    /**
     * The PR list loaded with a ✓ for the open PR from a head it had fetched before the latest push, or with a "?"
     * from a head older than the run's. The ✓ waits for the page's check, as the verdict head does; once the page
     * knows, the list badges the PR as the page heads it. A badge for anything but the head, such as a newer run still
     * going, stays the list's.
     */
    @Test
    void theListBadgesTheOpenPrAsThePageHeadsIt() throws Exception {
        String scenario = """
            const badges = listed([[13575, LISTED]]);
            page.route('/api/analyze', { body: ANALYSED });
            await page.load('?pr=13575');
            const before = badges[13575].slice();
            answerPending();
            await page.settle();
            report({ before, after: badges[13575] });
            """;

        JsonNode old = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + LIST
            + "const LISTED = 'CLEAN', ANALYSED = CLEAN; page.route('/api/pending', { body: AHEAD });" + scenario);
        JsonNode current = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + LIST
            + "const LISTED = 'OLD_CODE', ANALYSED = CLEAN;"
            + "page.route('/api/pending', { body: { pending: false, checked: true } });" + scenario);
        JsonNode live = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + LIST
            + "const LISTED = 'CLEAN', ANALYSED = verdict({ blockers: [], standing: 'UNPROVEN', live: true,"
            + "caveats: ['a newer run is still going — its unfinished suites can still fail'] });" + scenario);

        assertThat(marks(old.get("before"))).as("no ✓ while the check runs").containsExactly("…");
        assertThat(marks(old.get("after"))).containsExactly("…", "?");
        assertThat(old.get("after").get(1).asText()).contains("pr-badge unproven", "old code");
        assertThat(marks(current.get("before"))).isEmpty();
        assertThat(marks(current.get("after"))).containsExactly("✓");
        assertThat(marks(live.get("after"))).containsExactly("…", "✓");
    }

    /** The reader opened another PR, then went home, before GitHub answered: each left PR gets the list's badge back. */
    @Test
    void aPrLeftMidCheckGetsTheListsBadgeBack() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD + LIST + """
            const badges = listed([[13575, 'CLEAN'], [13576, 'CLEAN']]);
            page.route('/api/analyze', url => ({ body: Object.assign({}, CLEAN,
                { prNumber: +/pr=(\\d+)/.exec(url)[1] }) }));
            await page.load('?pr=13575');
            await page.run('openPr')(13576, true);
            await page.settle();
            const switched = { 13575: badges[13575].slice(), 13576: badges[13576].slice() };
            page.run('goHome')(true);
            answerPending();
            await page.settle();
            report({ switched, home: badges });
            """);

        assertThat(marks(out.get("switched").get("13575"))).containsExactly("…", "✓");
        assertThat(marks(out.get("switched").get("13576"))).containsExactly("…");
        assertThat(marks(out.get("home").get("13575"))).containsExactly("…", "✓");
        assertThat(marks(out.get("home").get("13576"))).containsExactly("…", "✓");
    }

    /** The marks of the badges drawn, as the reader sees them. */
    private static List<String> marks(JsonNode drawn) {
        List<String> marks = new ArrayList<>();
        drawn.forEach(html -> marks.add(html.asText().replaceAll("<[^>]*>", "")));
        return marks;
    }
}
