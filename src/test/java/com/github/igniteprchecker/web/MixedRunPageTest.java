package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * apache/ignite#13709: RunAll 9425992 tested c577b18, and the author re-ran Control Utility 5 on the PR's head
 * 9b02561. The page said "⚠ The verdict is for older code: this run tested c577b18, and the PR is now at 9b02561", while
 * the suite that had the blocker ran on that very head. It now says that this is a mixed run, which suites ran on which
 * commit, and that only a RunAll of the PR's head can prove it clean.
 */
class MixedRunPageTest {
    private static final String MIXED = """
        const RUN_ALL_COMMIT = 'c577b18e2f0d4a6b8c1d3e5f7a9b0c2d4e6f8a1b';
        const HEAD = '9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a';
        const CU5 = { suite: 'IgniteTests24Java8_ControlUtility5', suiteName: 'Control Utility 5', buildId: 9426316,
            revision: HEAD };
        const MIXED = verdict({ prNumber: 13575, buildId: 9425992, blockers: [], standing: 'UNPROVEN', caveats: [],
            revision: RUN_ALL_COMMIT, otherCodeRuns: [CU5] });
        const state = () => ({ head: page.el('verdictText').textContent, lines: page.el('verdictLines').textContent,
            line: page.el('pendingRow').classList.contains('hidden') ? null : page.el('pending').textContent,
            button: !page.el('pendingRerun').classList.contains('hidden'),
            primary: page.el('pendingRerun').classList.contains('primary'),
            merged: page.el('mergedNote').classList.contains('hidden') ? null : page.el('mergedNote').textContent });
        """;

    @Test
    void aMixedRunSaysWhichSuitesRanOnWhichCommit() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + """
            page.route('/api/analyze', { body: MIXED });
            page.route('/api/pending', { body: { pending: true, ahead: 3, builtSha: 'c577b18', headSha: '9b02561',
                rewritten: false } });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("lines").asText())
            .isEqualTo("This run can't prove the PR is clean: it is a mixed run (see the note at the top).");
        assertThat(out.get("line").asText()).isEqualTo("⚠ Mixed run: Control Utility 5 re-ran on 9b02561, the PR's "
            + "head; the rest of the RunAll ran on c577b18. Only a RunAll of the PR's head can prove it clean.");
        assertThat(out.get("button").asBoolean()).as("Run RunAll").isTrue();
        assertThat(out.get("primary").asBoolean()).as("the next step").isTrue();
    }

    /** Before the page knows the PR's head, and when the head moved past the re-run too. */
    @Test
    void theLineNamesThePrsHeadOnlyOnceItIsKnown() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + """
            const g = page.run('this');
            const fetchNow = g.fetch;
            let answerPending;
            const pendingAnswered = new Promise(r => { answerPending = r; });
            g.fetch = async (url, opts) => {
                if (String(url).startsWith('/api/pending')) await pendingAnswered;
                return fetchNow(url, opts);
            };
            page.route('/api/analyze', { body: MIXED });
            page.route('/api/pending', { body: { pending: true, ahead: 5, builtSha: 'c577b18', headSha: '4e1f0aa',
                rewritten: false } });
            await page.load('?pr=13575');
            const before = state();
            answerPending();
            await page.settle();
            report({ before, after: state() });
            """);

        assertThat(out.get("before").get("line").asText()).isEqualTo("⚠ Mixed run: Control Utility 5 re-ran on "
            + "9b02561; the rest of the RunAll ran on c577b18. Only a RunAll of the PR's head can prove it clean.");
        assertThat(out.get("before").get("head").asText()).isEqualTo("No blockers found — not proven clean");
        assertThat(out.get("after").get("line").asText()).isEqualTo("⚠ Mixed run: Control Utility 5 re-ran on "
            + "9b02561; the rest of the RunAll ran on c577b18. The PR is now at 4e1f0aa. Only a RunAll of the PR's head "
            + "can prove it clean.");
    }

    @Test
    void aRunAllAlreadyGoingIsTheNextStep() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + """
            page.route('/api/analyze', { body: MIXED });
            page.route('/api/pending', { body: { pending: true, ahead: 3, builtSha: 'c577b18', headSha: '9b02561',
                rewritten: false } });
            page.route('/api/runs', { body: [{ buildId: 9427000, runAll: true, state: 'running', name: 'RunAll',
                mine: true, webUrl: 'https://ci2.example/build/9427000' }] });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("line").asText()).isEqualTo("⚠ Mixed run: Control Utility 5 re-ran on 9b02561, the PR's "
            + "head; the rest of the RunAll ran on c577b18. A new RunAll is running.");
        assertThat(out.get("button").asBoolean()).isFalse();
    }

    /**
     * #13709 was merged at 10:12:39 UTC while its re-run went: the verdict kept at the merge takes the re-run's result
     * once it ends, and the page names it.
     */
    @Test
    void aMergedPrNamesTheRunsThatWereGoingAtTheMerge() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + """
            page.route('/api/analyze', { body: Object.assign(MIXED, { mergedAt: Math.floor(page.now() / 1000) - 86400,
                runsAtMerge: [CU5] }) });
            page.route('/api/pending', { body: { pending: true, ahead: 3, builtSha: 'c577b18', headSha: '9b02561',
                rewritten: false } });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("line").asText()).isEqualTo("⚠ Mixed run: Control Utility 5 re-ran on 9b02561, the commit "
            + "the PR was merged at; the rest of the RunAll ran on c577b18.");
        assertThat(out.get("button").asBoolean()).isFalse();
        assertThat(out.get("merged").asText()).isEqualTo("Merged 6 Oct 12:00. This is the verdict as it stood at the "
            + "merge, with the results of the runs that were still going then: Control Utility 5 on 9b02561. It is not "
            + "recomputed again: master's history now holds this PR's own runs. Runs queued after the merge do not "
            + "change this verdict.");
    }

    /** A RunAll cancelled at the merge leaves its finished suites: the note names three of them. */
    @Test
    void theNoteNamesThreeRunsAtTheMergeAtMost() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + """
            const runs = ['Cache 1', 'Cache 2', 'Cache 3', 'Cache 4', 'Cache 5'].map((suiteName, i) =>
                ({ suite: 'S' + i, suiteName, buildId: 9426400 + i, revision: HEAD }));
            page.route('/api/analyze', { body: Object.assign(MIXED, { mergedAt: Math.floor(page.now() / 1000) - 86400,
                runsAtMerge: runs }) });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("merged").asText()).contains("still going then: Cache 1 on 9b02561, Cache 2 on 9b02561, "
            + "Cache 3 on 9b02561 and 2 more runs. It is not recomputed again");
    }

    /** A new verdict of the same RunAll that is no longer mixed takes the line back to older code. */
    @Test
    void aVerdictThatIsNoLongerMixedLosesTheLine() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + """
            page.route('/api/analyze', { body: MIXED });
            page.route('/api/pending', { body: { pending: true, ahead: 3, builtSha: 'c577b18', headSha: '9b02561',
                rewritten: false } });
            await page.load('?pr=13575');
            page.run('renderResult')(Object.assign({}, MIXED, { otherCodeRuns: [] }), { quiet: true });
            report(state());
            """);

        assertThat(out.get("line").asText()).isEqualTo("⚠ The verdict is for older code: this run tested c577b18, and "
            + "the PR is now at 9b02561, 3 commits later.");
        assertThat(out.get("lines").asText())
            .isEqualTo("This run can't prove the PR is clean: it tested older code (see the note at the top).");
    }

    @Test
    void theRussianPageSaysItToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + MIXED + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/me', { body: { username: 'alice', admin: false, jiraInCookie: false, lang: 'ru' } });
            page.route('/api/analyze', { body: Object.assign(MIXED, { otherCodeRuns: [CU5,
                { suite: 'Q1', suiteName: 'Queries 1', buildId: 9426500, revision: '1a2b3c4d5e' }] }) });
            page.route('/api/pending', { body: { pending: true, ahead: 3, builtSha: 'c577b18', headSha: '9b02561',
                rewritten: false } });
            await page.load('?pr=13575');
            report(state());
            """);

        assertThat(out.get("line").asText()).isEqualTo("⚠ Смешанный прогон: Control Utility 5 перезапустили на "
            + "9b02561 — последнем коммите PR; Queries 1 на 1a2b3c4; остальные сьюты RunAll прошли на c577b18. "
            + "Доказать, что PR чист, может только RunAll последнего коммита PR.");
        assertThat(out.get("lines").asText())
            .isEqualTo("Этот прогон не может доказать, что PR чист: это смешанный прогон (см. строку наверху).");
    }
}
