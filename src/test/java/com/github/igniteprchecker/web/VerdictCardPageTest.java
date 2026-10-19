package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * On PR 13692 the answer to "what did this PR break" stood after Suites that never ran, Broken suites, Fewer tests
 * and Crashed near the end, the blocker count was repeated in three headings, the JIRA visa buttons hung over the
 * blocker list beside the view tabs, and "vs previous run: no new · none fixed · 0 persisting" said nothing. The
 * verdict card now comes first, with the one heading that carries the count, the visa line at its right, the
 * caveats under it and the comparison with the previous RunAll; the Blockers card keeps only the lists.
 */
class VerdictCardPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /** Sets the PR title, which names the ticket, as the list would; the harness keeps no list items. */
    private static final String TICKET = """
        page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
        await page.run('loadVisaLine')(13575);
        """;

    @Test
    void theVerdictCardComesFirstAndTheOtherCardsFollowInOrder() throws IOException {
        String results = Files.readString(PAGE, UTF_8);
        results = results.substring(results.indexOf("<div id=\"results\""));
        List<String> ids = List.of("verdictCard", "interruptedBanner", "brokenCard", "shrunkCard", "blockersCard",
            "watchCard", "unverifiedCard", "prTestsCard", "unstableCard", "filteredCard");

        int at = -1;
        for (String id : ids) {
            int next = results.indexOf("id=\"" + id + "\"");
            assertThat(next).as(id).isGreaterThan(at);
            at = next;
        }
        assertThat(results).contains("<span id=\"blockersHead\">Blockers</span>").doesNotContain("blockerCount");
        assertThat(results).contains("<span id=\"blockerTabs\" class=\"group-by hidden\"><span class=\"muted\">Group by:</span>");
        assertThat(results).containsPattern("<div id=\"shrunkCard\" class=\"card watch hidden\"");
        assertThat(results).containsPattern("<div id=\"unstableCard\" class=\"card hidden\">\\s*<details>\\s*<summary>"
            + "Crashed near the end of the run <span id=\"unstableCount\"></span> — results stand");
    }

    @Test
    void theBlockersCardShowsOnlyWithBlockersAndCarriesNoCount() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const withBlockers = { hidden: page.el('blockersCard').classList.contains('hidden'),
                head: page.el('verdictText').textContent };
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'CLEAN', caveats: [],
                computedAt: page.now() }) });
            await page.run('analyze')(13575);
            report({ withBlockers, without: page.el('blockersCard').classList.contains('hidden') });
            """);

        assertThat(out.get("withBlockers").get("hidden").asBoolean()).isFalse();
        assertThat(out.get("withBlockers").get("head").asText()).isEqualTo("1 blocker in 1 suite");
        assertThat(out.get("without").asBoolean()).isTrue();
    }

    /** Thirty-three blockers of PR 13692, each on its only run: the heading counts them as the visa does. */
    @Test
    void theHeadingCountsBlockersBySuiteAndSaysHowManyRestOnOneRun() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const once = (name, occ, suite) => Object.assign(blocker(name, occ), { suite, suiteBuildId: suite.length,
                suiteName: suite, branchRuns: 'F', codeRuns: 1, doubts: ['ONE_RUN'] });
            page.route('/api/analyze', { body: verdict({ blockers: [once('A.a', 'o1', 'Python'), once('A.b', 'o2', 'Python'),
                once('B.c', 'o3', 'Node')] }) });
            await page.load('?pr=13575');
            const all = { head: page.el('verdictText').textContent, note: page.el('oneRunNote').classList.contains('hidden'),
                group: page.el('oneRunGroup').classList.contains('hidden'), list: page.el('blockers').textContent,
                acts: page.el('oneRunActs').classList.contains('hidden') };
            page.run('setBlockerView')('causes');
            const inCauses = page.el('oneRunNote').classList.contains('hidden');
            page.run('setBlockerView')('suites');
            page.route('/api/analyze', { body: verdict({ computedAt: page.now(), blockers: [once('A.a', 'o1', 'Python'),
                once('A.b', 'o2', 'Python'), blocker('B.c', 'o3')] }) });
            await page.run('analyze')(13575);
            report({ all, inCauses, mixed: { head: page.el('verdictText').textContent,
                note: page.el('oneRunNote').classList.contains('hidden'),
                group: page.el('oneRunGroup').classList.contains('hidden'), count: page.el('oneRunCount').textContent,
                acts: page.el('oneRunActs').classList.contains('hidden') } });
            """);

        JsonNode all = out.get("all");
        assertThat(all.get("head").asText()).isEqualTo("3 blockers in 2 suites, all on 1 run");
        assertThat(all.get("note").asBoolean()).as("the one line says it for every blocker").isFalse();
        assertThat(all.get("group").asBoolean()).as("no second heading, no second Rerun pair").isTrue();
        assertThat(all.get("acts").asBoolean()).isTrue();
        assertThat(all.get("list").asText()).contains("A.a", "A.b", "B.c");
        assertThat(out.get("inCauses").asBoolean()).as("said in Root causes too").isFalse();

        JsonNode mixed = out.get("mixed");
        assertThat(mixed.get("head").asText()).isEqualTo("3 blockers in 2 suites, 2 of them on 1 run");
        assertThat(mixed.get("note").asBoolean()).isTrue();
        assertThat(mixed.get("group").asBoolean()).isFalse();
        assertThat(mixed.get("count").asText()).isEqualTo("(1 suite, 2 tests)");
        assertThat(mixed.get("acts").asBoolean()).as("Python is not the card's whole set").isFalse();
    }

    /** Both blockers of the card are in one suite, one of them on its only run: one Rerun pair re-runs that suite. */
    @Test
    void theNotConfirmedPairIsLeftOutWhenItWouldReRunTheCardsSuites() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [blocker('A.a', 'o1'),
                Object.assign(blocker('A.b', 'o2'), { branchRuns: 'F', codeRuns: 1, doubts: ['ONE_RUN'] })] }) });
            await page.load('?pr=13575');
            report({ group: page.el('oneRunGroup').classList.contains('hidden'),
                acts: page.el('oneRunActs').classList.contains('hidden') });
            """);

        assertThat(out.get("group").asBoolean()).isFalse();
        assertThat(out.get("acts").asBoolean()).isTrue();
    }

    /** The visa line stands in the verdict's heading instead of the visa buttons; with no ticket it says so. */
    @Test
    void theVisaLineSitsInTheVerdictHeading() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'Move MDC to JUnit', null);
            await page.run('loadVisaLine')(13575);
            const noTicket = { hidden: page.el('visaLine').classList.contains('hidden'),
                text: page.el('visaLine').textContent, asked: page.fetches('/api/visa').length };
            """ + TICKET + """
            report({ noTicket, due: page.el('visaLine').textContent });
            """);

        String page = Files.readString(PAGE, UTF_8);
        int head = page.indexOf("<h2 id=\"verdictHead\">");
        assertThat(page.substring(head, page.indexOf("</h2>", head)))
            .contains("<span id=\"visaLine\" class=\"visa-group muted hidden\">");
        assertThat(page).doesNotContain("id=\"visaBtn\"", "id=\"autoVisaBtn\"", "Post now", "JIRA visa:");
        assertThat(out.get("noTicket").get("hidden").asBoolean()).isFalse();
        assertThat(out.get("noTicket").get("text").asText()).isEqualTo("Visa: the PR title names no IGNITE ticket");
        assertThat(out.get("noTicket").get("asked").asInt()).as("the title alone tells it").isZero();
        assertThat(out.get("due").asText()).isEqualTo("Visa: will be posted to IGNITE-29049 when the run settles");
    }

    /** The run settled while the page was open: the next poll of the runs repaints the line. */
    @Test
    void theVisaLineFollowsTheRunsAsTheyArePolled() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            """ + TICKET + """
            const due = page.el('visaLine').textContent;
            page.route('/api/visa', { body: { state: 'posted', starter: 'alice', mine: true, issue: 'IGNITE-29049',
                url: 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=1#comment-1' } });
            await page.run('loadRuns')(13575);
            await page.settle();
            report({ due, posted: page.el('visaLine').innerHTML });
            """);

        assertThat(out.get("due").asText()).isEqualTo("Visa: will be posted to IGNITE-29049 when the run settles");
        assertThat(out.get("posted").asText()).isEqualTo("Visa: posted to <a href=\"https://issues.apache.org/jira/"
            + "browse/IGNITE-29049?focusedCommentId=1#comment-1\" target=\"_self\" rel=\"noopener\" title=\"Opens the "
            + "visa in JIRA.\">IGNITE-29049</a>");
    }

    /** The view switch says which view is on to a screen reader too, not only by its fill. */
    @Test
    void theViewTabsTellAScreenReaderWhichViewIsOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/analyze', { body: verdict({ blockers: [blocker('A.a', 'o1'), blocker('A.b', 'o2')] }) });
            page.route('/api/causes', { body: { total: 2, sampled: 2, noMessage: [], unread: [], runStartedAt: {},
                clusters: [{ signature: 'Partition N lost', count: 2, tests: [] }] } });
            await page.load('?pr=13575');
            const pressed = () => [page.el('tabSuites').getAttribute('aria-pressed'), page.el('tabCauses').getAttribute('aria-pressed')];
            const suites = pressed();
            page.run('setBlockerView')('causes');
            await page.settle();
            const causes = pressed();
            page.run('setBlockerView')('suites');
            report({ suites, causes, back: pressed() });
            """);

        assertThat(Files.readString(PAGE, UTF_8))
            .contains("<button id=\"tabSuites\" class=\"act on\" type=\"button\" aria-pressed=\"true\"")
            .contains("<button id=\"tabCauses\" class=\"act\" type=\"button\" aria-pressed=\"false\"");
        assertThat(out.get("suites").toString()).isEqualTo("[\"true\",\"false\"]");
        assertThat(out.get("causes").toString()).isEqualTo("[\"false\",\"true\"]");
        assertThat(out.get("back").toString()).isEqualTo("[\"true\",\"false\"]");
    }

    @Test
    void theComparisonWithThePreviousRunAllSaysOnlyWhatChanged() throws Exception {
        String changed = "const changed = (name, suite) => ({ name: 'org.apache.ignite.' + name, suite, suiteName: suite });";
        String scenario = """
            page.route('/api/delta', { body: { delta: Object.assign({ prevBuildId: 9000, latestBuildId: 9001,
                appeared: [], fixed: [], persisting: 0 }, DELTA), history: HISTORY } });
            await page.load('?pr=13575');
            report({ text: page.el('delta').textContent, html: page.el('delta').innerHTML,
                hidden: page.el('deltaRow').classList.contains('hidden'), bars: page.el('trend').innerHTML });
            """;
        String twoRuns = "[{ buildId: 9000, at: page.now() - 86400000, blockers: 5, brokenSuites: 1 },"
            + " { buildId: 9001, at: page.now(), blockers: 1, brokenSuites: 0 }]";

        JsonNode some = PageScript.run("index.html", PageScript.SIGNED_IN + changed
            + "const DELTA = { appeared: [changed('A.a', 'Cache')], fixed: [changed('B.b', 'Cache'), changed('C.c', 'PDS')],"
            + " persisting: 33 }; const HISTORY = " + twoRuns + ";" + scenario);
        JsonNode nothing = PageScript.run("index.html", PageScript.SIGNED_IN + changed
            + "const DELTA = {}; const HISTORY = " + twoRuns + ";" + scenario);
        JsonNode onlyGone = PageScript.run("index.html", PageScript.SIGNED_IN + changed
            + "const DELTA = { fixed: [changed('B.b', 'Cache')] }; const HISTORY = [];" + scenario);
        JsonNode threeRuns = PageScript.run("index.html", PageScript.SIGNED_IN + changed
            + "const DELTA = {}; const HISTORY = [{ buildId: 8999, at: page.now() - 172800000, blockers: 9, brokenSuites: 0 },"
            + " { buildId: 9000, at: page.now() - 86400000, blockers: 5, brokenSuites: 1 },"
            + " { buildId: 9001, at: page.now(), blockers: 1, brokenSuites: 0 }];" + scenario);

        assertThat(some.get("text").asText())
            .isEqualTo("Blockers since the previous RunAll: 1 new · 2 dropped out · 33 still blocking");
        assertThat(some.get("html").asText())
            .contains("title=\"Open the previous RunAll build in TeamCity\">previous RunAll</a>")
            .contains("<span class=\"delta-new\" title=\"A.a — Cache\">1 new</span>")
            .contains("<span class=\"delta-fixed\" title=\"No longer blockers: passed, moved to another list, or did not run.\n"
                + "B.b — Cache\nC.c — PDS\">2 dropped out</span>");
        assertThat(some.get("bars").asText()).as("two runs make no bars").isEmpty();

        assertThat(nothing.get("hidden").asBoolean()).as("all three zero, two runs: nothing to say").isTrue();
        assertThat(onlyGone.get("text").asText())
            .isEqualTo("Blockers since the previous RunAll: no new · 1 dropped out");

        assertThat(threeRuns.get("text").asText()).isEqualTo("Blockers per RunAll:");
        assertThat(threeRuns.get("bars").asText())
            .contains("title=\"RunAll 9000, 6 Oct: 5 blockers, 1 broken suite\"", "title=\"RunAll 9001, 7 Oct: 1 blocker\"")
            .contains("style=\"--h:16px\"", "style=\"--h:4px\"");
    }

    /**
     * PR 13575's comparison stood on PR 13461's card: the row lives in the results now, and the service, restarting,
     * answered 502 to the new PR's delta. The row is cleared with the PR, and an answer for a verdict no longer on
     * screen is dropped.
     */
    @Test
    void theComparisonOfOnePrIsNotShownOnTheNext() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            const first = verdict();
            const delta = { prevBuildId: 9000, latestBuildId: 9001, appeared: [{ name: 'org.apache.ignite.A.a', suite: 'Cache' }],
                fixed: [], persisting: 33 };
            page.route('/api/delta', { body: { delta, history: [] } });
            await page.load('?pr=13575');
            const shown = { text: page.el('delta').textContent, hidden: page.el('deltaRow').classList.contains('hidden') };
            page.route('/api/analyze', { body: verdict({ prNumber: 13461, buildId: 7001, blockers: [], standing: 'CLEAN',
                caveats: [] }) });
            page.route('/api/delta', { status: 502, body: { error: 'Bad Gateway' } });
            await page.run('openPr')(13461, true);
            await page.settle();
            const next = { text: page.el('delta').textContent, bars: page.el('trend').innerHTML,
                hidden: page.el('deltaRow').classList.contains('hidden'), results: page.el('results').classList.contains('hidden') };
            page.route('/api/delta', { body: { delta, history: [] } });
            await page.run('loadDelta')(first);
            report({ shown, next, late: { text: page.el('delta').textContent, hidden: page.el('deltaRow').classList.contains('hidden') } });
            """);

        assertThat(out.get("shown").get("text").asText()).isEqualTo("Blockers since the previous RunAll: 1 new · 33 still blocking");
        assertThat(out.get("shown").get("hidden").asBoolean()).isFalse();

        JsonNode next = out.get("next");
        assertThat(next.get("results").asBoolean()).as("the next PR's results are on screen").isFalse();
        assertThat(next.get("hidden").asBoolean()).as("13575's comparison shown on 13461: " + next.get("text").asText()).isTrue();
        assertThat(next.get("text").asText()).isEmpty();
        assertThat(next.get("bars").asText()).isEmpty();

        assertThat(out.get("late").get("hidden").asBoolean()).as("a late answer for the PR that was left").isTrue();
        assertThat(out.get("late").get("text").asText()).isEmpty();
    }

    /** The desktop notification tells WATCH from "not proven clean", as the tab's title does. */
    @Test
    void theNotificationTellsTestsThatStartedFailingFromNotProvenClean() throws Exception {
        String scenario = """
            const shown = [];
            page.run('window').Notification = class {
                constructor(title, opts) { shown.push(title); }
                close() {}
            };
            page.run('window').Notification.permission = 'granted';
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'running', name: 'RunAll', mine: true,
                webUrl: 'https://ci2.example/build/9100', leftSec: 60, pct: 90 }] });
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            page.route('/api/runs', { body: [] });
            page.route('/api/refresh', { body: FINAL }, 'POST');
            page.route('/api/analyze', { body: FINAL });
            await page.tick(15000);
            await page.tick(90000);
            report({ shown, title: page.run('document').title });
            """;

        JsonNode watch = PageScript.run("index.html", PageScript.SIGNED_IN + "const FINAL = verdict({ buildId: 9100, blockers: [],"
            + " standing: 'WATCH', caveats: [], watch: [Object.assign(blocker('T.a', 'o1'), { blocker: false, watch: true }),"
            + " Object.assign(blocker('T.b', 'o2'), { blocker: false, watch: true })] });" + scenario);
        JsonNode unproven = PageScript.run("index.html", PageScript.SIGNED_IN + "const FINAL = verdict({ buildId: 9100,"
            + " blockers: [], standing: 'UNPROVEN', caveats: ['1 suite has no reliable result (execution timeout)'] });"
            + scenario);

        assertThat(watch.get("shown").toString())
            .isEqualTo("[\"PR #13575: 2 tests started failing. A re-run shows whether they are blockers.\"]");
        assertThat(watch.get("title").asText()).isEqualTo("⚠ started failing · #13575 — Ignite PR Checker");
        assertThat(unproven.get("shown").toString())
            .isEqualTo("[\"PR #13575: no blockers found, not proven clean. Open the page to see why.\"]");
        assertThat(unproven.get("title").asText()).isEqualTo("⚠ not proven clean · #13575 — Ignite PR Checker");
    }
}
