package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The head of PR 13692's page had four chips beside text that could be the link itself, the run line was pushed to
 * the right and said "suites: 147 fresh, 0 from earlier runs" on every PR, an error from Run sat grey in the same
 * line, "⚠ 9 new commits pushed since this run" counted master's commits after a rebase and kept its button far to
 * the right while a new RunAll was already queued, and a failed analysis told the reader to press a ↻ that was not
 * on screen. The number and the ticket key are the links, the run line stands under the title, errors are red and
 * start with what did not happen, the older-code line says what the run tested and what the PR is at now, and a
 * failed analysis offers Retry.
 */
class PrHeadPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    @Test
    void theNumberAndTheTicketKeyAreTheLinks() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', 'https://github.com/apache/ignite/pull/13575');
            report({ html: page.el('prTitle').innerHTML, text: page.el('prTitle').textContent });
            """);

        assertThat(out.get("text").asText()).isEqualTo("#13575 IGNITE-29049 Move MDC to JUnit");
        assertThat(out.get("html").asText())
            .contains("<a href=\"https://github.com/apache/ignite/pull/13575\" target=\"_self\" rel=\"noopener\" "
                + "title=\"Open PR #13575 on GitHub\">#13575</a>")
            .contains("<a href=\"https://issues.apache.org/jira/browse/IGNITE-29049\" target=\"_self\" rel=\"noopener\" "
                + "title=\"Open IGNITE-29049 in JIRA\">IGNITE-29049</a>")
            .doesNotContain("class=\"ext\"", ">GitHub<", ">JIRA<");
    }

    @Test
    void theRunLineStartsWithTheRunAllBuildAndSaysReusedSuitesOnlyWhenThereAre() throws Exception {
        String scenario = """
            page.route('/api/analyze', { body: verdict(RUN) });
            await page.load('?pr=13575');
            report({ text: page.el('freshText').textContent, html: page.el('freshText').innerHTML,
                more: page.el('freshMore').textContent, moreHtml: page.el('freshMore').innerHTML });
            """;

        JsonNode reused = PageScript.run("index.html", PageScript.SIGNED_IN
            + "const RUN = { suitesRan: 135, suitesReused: 12 };" + scenario);
        JsonNode none = PageScript.run("index.html", PageScript.SIGNED_IN
            + "const RUN = { suitesRan: 147, suitesReused: 0 };" + scenario);
        JsonNode going = PageScript.run("index.html", PageScript.SIGNED_IN
            + "const RUN = { suitesRan: 23, suitesReused: 0, live: true, liveBuildId: 9001, finishedAt: 0 };" + scenario);

        assertThat(reused.get("text").asText())
            .isEqualTo("RunAll build 9001 · finished 7 Oct 11:00 (1h ago) · verdict computed 30s ago");
        assertThat(reused.get("html").asText())
            .contains("<a href=\"https://ci2.example/build/9001\" target=\"_self\" rel=\"noopener\" "
                + "title=\"Open this RunAll build in TeamCity\">RunAll build 9001</a>")
            .contains("title=\"When the checker last computed this verdict from the run and master's history. ↻ computes "
                + "it again.\">verdict computed 30s ago</span>");
        assertThat(reused.get("more").asText()).isEqualTo(" · 135 suites ran, 12 reused from earlier runs");
        assertThat(reused.get("moreHtml").asText()).contains("title=\"TeamCity took the results of reused suites from "
            + "earlier runs of the same code instead of running them again.\"");
        assertThat(Files.readString(PAGE, UTF_8)).contains("<button id=\"refreshBtn\" class=\"refresh hidden\" type=\"button\"\n"
            + "                        title=\"Compute the verdict again now\" aria-label=\"Compute the verdict again now\">↻</button>");
        assertThat(none.get("more").asText()).as("nothing reused: nothing to say").isEmpty();
        assertThat(going.get("text").asText()).contains("started 7 Oct 09:20 (3h ago)");
        assertThat(going.get("more").asText()).isEqualTo(" · 23 suites ran · ● includes an unfinished run");
    }

    @Test
    void anErrorInTheStatusLineIsRedAndStartsWithWhatDidNotHappen() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.run('window').confirm = () => true;
            page.route('/api/trigger', { status: 502, body: { error: 'Bad Gateway' } }, 'POST');
            await page.load('?pr=13575');
            await page.run('doTrigger')('trigger', 'false', page.el('runBtn'));
            const blip = { text: page.el('status').textContent, err: page.el('status').classList.contains('err') };
            page.route('/api/trigger', { status: 403, body: { error: 'ci2 refused to queue it (403)' } }, 'POST');
            await page.run('doTrigger')('trigger', 'false', page.el('runBtn'));
            const refused = { text: page.el('status').textContent, err: page.el('status').classList.contains('err') };
            page.route('/api/trigger', { body: { triggered: [{ buildId: 501, webUrl: 'https://ci2.example/build/501' }] } },
                'POST');
            await page.run('doTrigger')('trigger', 'false', page.el('runBtn'));
            report({ blip, refused, queued: { text: page.el('status').textContent,
                err: page.el('status').classList.contains('err') } });
            """);

        assertThat(out.get("blip").get("text").asText())
            .isEqualTo("⚠ Not queued. The service may be restarting. Try again in a moment.");
        assertThat(out.get("blip").get("err").asBoolean()).isTrue();
        assertThat(out.get("refused").get("text").asText()).isEqualTo("⚠ Not queued: ci2 refused to queue it (403)");
        assertThat(out.get("refused").get("err").asBoolean()).isTrue();
        assertThat(out.get("queued").get("text").asText()).isEqualTo("Queued 1 build: #501.");
        assertThat(out.get("queued").get("err").asBoolean()).as("a success clears the red").isFalse();
    }

    @Test
    void theOlderCodeLineSaysWhatTheRunTestedAndHidesItsButtonWhileARunAllIsQueued() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/pending', { body: { pending: true, ahead: 9, builtSha: 'ac9f829', headSha: '5ae5733',
                rewritten: false } });
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'queued', name: 'RunAll', mine: true,
                webUrl: 'https://ci2.example/build/9100', startSec: 600 }] });
            await page.load('?pr=13575');
            const queued = { text: page.el('pending').textContent, html: page.el('pending').innerHTML,
                button: page.el('pendingRerun').classList.contains('hidden'),
                primary: page.el('pendingRerun').classList.contains('primary') };
            page.route('/api/runs', { body: [] });
            await page.tick(15000);
            const idle = { text: page.el('pending').textContent, button: page.el('pendingRerun').classList.contains('hidden'),
                primary: page.el('pendingRerun').classList.contains('primary') };
            page.route('/api/pending', { body: { pending: true, ahead: 150, builtSha: '1f93640', headSha: 'b20125e',
                rewritten: true } });
            await page.run('loadPending')(page.run('lastResult'));
            report({ queued, idle, rebased: page.el('pending').textContent });
            """);

        JsonNode queued = out.get("queued");
        assertThat(queued.get("text").asText()).isEqualTo("⚠ The verdict is for older code: this run tested ac9f829, and "
            + "the PR is now at 5ae5733, 9 commits later. A new RunAll is queued.");
        assertThat(queued.get("html").asText()).startsWith("<b>⚠ The verdict is for older code:</b> this run tested");
        assertThat(queued.get("button").asBoolean()).as("Run RunAll while one is queued").isTrue();
        assertThat(queued.get("primary").asBoolean()).isFalse();

        JsonNode idle = out.get("idle");
        assertThat(idle.get("text").asText()).isEqualTo("⚠ The verdict is for older code: this run tested ac9f829, and "
            + "the PR is now at 5ae5733, 9 commits later.");
        assertThat(idle.get("button").asBoolean()).isFalse();
        assertThat(idle.get("primary").asBoolean()).as("the next step").isTrue();

        assertThat(out.get("rebased").asText()).isEqualTo("⚠ The verdict is for older code: this run tested 1f93640, and "
            + "the PR branch was rebased or force-pushed to b20125e.");
    }

    @Test
    void theRunsRowNamesTheCommitEachRunTestsInWords() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const run = (id, state, extra) => Object.assign({ buildId: id, state, name: 'Cache', btId: 'Cache',
                webUrl: 'https://ci2.example/build/' + id, pct: -1, leftSec: -1, startSec: -1, waitedSec: -1,
                elapsedSec: -1, by: 'alice', mine: true }, extra);
            page.route('/api/runs', { body: [run(1, 'running', { onHead: true, rev: '5ae5733' }),
                run(2, 'running', { onHead: false, rev: 'ac9f829' }), run(3, 'queued', {})] });
            await page.load('?pr=13575');
            report({ text: page.el('runs').textContent, html: page.el('runs').innerHTML });
            """);

        assertThat(out.get("text").asText()).startsWith("On ci2 now: ")
            .contains("on latest commit", "⚠ old commit ac9f829", "takes latest commit at start")
            .doesNotContain("rev ");
        assertThat(out.get("html").asText())
            .contains("title=\"This run tests the PR's latest commit.\"")
            .contains("title=\"Started on ac9f829. Commits were pushed after, so this run tests older code.\"")
            .contains("title=\"TeamCity picks the commit when the run starts: the PR's latest commit at that moment.\"");
    }

    @Test
    void aFailedAnalysisOffersRetryInsteadOfAButtonThatIsNotThere() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            let busy = null;
            page.route('/api/analyze', () => { busy = { text: page.el('status').textContent,
                cls: page.el('status').classList.contains('busy') }; return { status: 502, body: { error: 'Bad Gateway' } }; });
            await page.load('?pr=13575');
            const retrying = { text: page.el('status').textContent, cls: page.el('status').classList.contains('busy') };
            await page.tick(30000);
            const failed = { text: page.el('status').textContent, err: page.el('status').classList.contains('err'),
                retry: page.el('statusRetry').classList.contains('hidden'), asked: page.fetches('/api/analyze').length };
            page.route('/api/analyze', { body: verdict() });
            await page.el('statusRetry').onclick();
            await page.settle();
            report({ busy, retrying, failed, after: { status: page.el('status').textContent,
                retry: page.el('statusRetry').classList.contains('hidden'),
                results: page.el('results').classList.contains('hidden') } });
            """);

        assertThat(out.get("busy").get("text").asText()).isEqualTo("Analyzing the latest RunAll…");
        assertThat(out.get("busy").get("cls").asBoolean()).isTrue();
        assertThat(out.get("retrying").get("text").asText())
            .isEqualTo("The service may be busy or restarting. Retrying…");
        assertThat(out.get("retrying").get("cls").asBoolean()).isTrue();

        JsonNode failed = out.get("failed");
        assertThat(failed.get("text").asText()).isEqualTo("Could not analyze this PR (HTTP 502).");
        assertThat(failed.get("err").asBoolean()).isTrue();
        assertThat(failed.get("retry").asBoolean()).as("Retry shown").isFalse();
        assertThat(failed.get("asked").asInt()).isEqualTo(7);

        JsonNode after = out.get("after");
        assertThat(after.get("status").asText()).isEmpty();
        assertThat(after.get("retry").asBoolean()).isTrue();
        assertThat(after.get("results").asBoolean()).isFalse();
    }

    /** The settling line keeps the name TeamCity, the PR comment and ⚙ use, in plain words. */
    @Test
    void theAutoReRunLineIsPlainWords() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: { phase: 'settling', wave: 1, of: 2, what: '2 blocker suites',
                etaEpochSec: Math.floor(page.now() / 1000) + 20 * 60 } });
            await page.load('?pr=13575');
            report({ text: page.el('settling').textContent });
            """);

        assertThat(out.get("text").asText()).isEqualTo("♻︎ Auto re-run #1 of up to 2: re-running 2 blocker suites, done in "
            + "~20m. The verdict below may change when they finish.");
    }

    /** The status line and the ↻ have their own places under the title; ↻ keeps its id for the merged-PR rule. */
    @Test
    void theStatusLineStandsUnderTheRunLine() throws IOException {
        String page = Files.readString(PAGE, UTF_8);

        assertThat(page.indexOf("id=\"freshness\"")).isLessThan(page.indexOf("id=\"status\""));
        assertThat(page).contains("<span id=\"freshText\"></span><button id=\"refreshBtn\" class=\"refresh hidden\"")
            .contains("<button id=\"statusRetry\" class=\"act hidden\" type=\"button\"")
            .contains(".status-row:not(:has(> #status:not(:empty))) { display: none; }");
    }
}
