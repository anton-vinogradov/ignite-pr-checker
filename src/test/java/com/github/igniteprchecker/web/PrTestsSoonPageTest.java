package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * On PR 13335 "This PR's tests" came late, after the verdict. The page asked for it only once it had drawn the
 * verdict and asked for the delta, the pending commits and the auto visa. It is now asked first, the verdict does not
 * wait for it, and a new verdict of the same run says anew what it did with the failed tests without asking again.
 */
class PrTestsSoonPageTest {
    /** PR 13335's SslContextTest, whose testReloadExpired failed in the RunAll. */
    private static final String SSL = """
        const failed = { testId: '301',
            name: 'SecurityTestSuite: org.apache.ignite.internal.ssl.SslContextTest.testReloadExpired',
            suite: 'IgniteTests24Java8_Security', suiteBuildId: 9002, suiteName: 'Security', occurrenceId: 'occ-301',
            status: 'FAILURE', durationMs: 3000, masterRuns: 40, muted: false };
        const prTests = { buildId: 9001, note: null, runsKnown: true, classes: [{
            name: 'org.apache.ignite.internal.ssl.SslContextTest', path: 'SslContextTest.java', added: false,
            runs: [failed], notInSuite: false, atRun: null }] };
        const filtered = Object.assign({}, failed, { name: failed.name, blocker: false, watch: false,
            reason: 'pre-existing: fails 3/100 on master', branchRuns: 'F', codeRuns: 1 });
        """;

    @Test
    void theCardIsAskedBeforeTheVerdictIsDrawn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            let atAsk = null;
            page.route('/api/pr-tests', url => {
                const urls = page.fetches('/').map(f => f.url);
                atAsk = { verdict: page.el('verdictText').textContent,
                    before: urls.slice(urls.indexOf('/api/analyze?pr=13575') + 1) };
                return { body: prTests };
            });
            await page.load('?pr=13575');
            report({ atAsk, verdict: page.el('verdictText').textContent });
            """);

        assertThat(out.get("atAsk").get("verdict").asText()).as("the verdict at the ask").isEmpty();
        assertThat(out.get("atAsk").get("before")).extracting(JsonNode::asText)
            .noneMatch(url -> url.startsWith("/api/delta") || url.startsWith("/api/pending")
                || url.startsWith("/api/auto-visa") || url.startsWith("/api/settling"));
        assertThat(out.get("verdict").asText()).isNotEmpty();
    }

    @Test
    void theVerdictDoesNotWaitForTheCard() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/pr-tests', () => new Promise(() => {}));
            await page.load('?pr=13575');
            report({ verdict: page.el('verdictText').textContent,
                shown: !page.el('results').classList.contains('hidden'), asked: page.fetches('/api/pr-tests').length });
            """);

        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("verdict").asText()).isEqualTo("1 blocker in 1 suite");
    }

    /**
     * The verdict was computed before the Security suite's failure was judged; the next one, of the same RunAll, puts
     * it under Not blamed. The card says so without asking the server about the PR's tests again.
     */
    @Test
    void aNewVerdictOfTheSameRunSaysAnewWhatItDid() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            page.route('/api/pr-tests', { body: prTests });
            await page.load('?pr=13575');
            const before = page.el('prTests').textContent;
            page.route('/api/analyze', { body: verdict({ filtered: [filtered] }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            report({ before, after: page.el('prTests').textContent, asked: page.fetches('/api/pr-tests').length });
            """);

        assertThat(out.get("before").asText()).endsWith("1 failed (not checked) in Security · slowest test 3 s"
            + "SslContextTest.testReloadExpiredfailed3 snot checked");
        assertThat(out.get("after").asText()).contains("1 failed (not blamed)",
            "testReloadExpiredfailed3 snot blamed: pre-existing: fails 3/100 on master");
        assertThat(out.get("asked").asInt()).isEqualTo(1);
    }

    /**
     * The answer about the PR's tests is still on its way when a quiet look brings a new verdict of the same RunAll,
     * which puts the Security suite's failure under Not blamed. The card draws the answer with the verdict on screen,
     * not with the one that asked for it, so it agrees with Not blamed on this PR.
     */
    @Test
    void aLateAnswerIsDrawnWithTheVerdictOnScreen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + SSL + """
            let answer;
            const answered = new Promise(resolve => { answer = resolve; });
            page.route('/api/pr-tests', async () => {
                await answered;
                return { body: prTests };
            });
            await page.load('?pr=13575');
            page.route('/api/analyze', { body: verdict({ filtered: [filtered] }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            answer();
            await page.settle();
            report({ text: page.el('prTests').textContent, asked: page.fetches('/api/pr-tests').length });
            """);

        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.get("text").asText()).contains("1 failed (not blamed)",
            "testReloadExpiredfailed3 snot blamed: pre-existing: fails 3/100 on master");
    }
}
