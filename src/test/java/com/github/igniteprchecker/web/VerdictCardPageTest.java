package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * On PR 13692 the answer to "what did this PR break" stood after Suites that never ran, Broken suites, Fewer tests
 * and Crashed near the end, the blocker count was repeated in three headings, the JIRA visa buttons hung over the
 * blocker list beside the view tabs, and "vs previous run: no new · none fixed · 0 persisting" said nothing. The
 * verdict card now comes first, with the one heading that carries the count, the visa group at its right, the
 * caveats under it and the comparison with the previous RunAll; the Blockers card keeps only the lists.
 */
class VerdictCardPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /** Sets the PR title, which names the ticket, as the list would; the harness keeps no list items. */
    private static final String TICKET = """
        page.run('setPrTitle')(13575, 'IGNITE-29049 Move MDC to JUnit', null);
        page.run('paintVisaGroup')();
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

    @Test
    void theVisaGroupSitsInTheVerdictCardAndSaysWhenItWillPost() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('setPrTitle')(13575, 'Move MDC to JUnit', null);
            page.run('paintVisaGroup')();
            const noTicket = { group: page.el('visaGroup').classList.contains('hidden'),
                line: page.el('noTicket').classList.contains('hidden') };
            """ + TICKET + """
            const idle = { group: page.el('visaGroup').classList.contains('hidden'),
                postTitle: page.el('visaBtn').title, auto: page.el('autoVisaBtn').textContent,
                pressed: page.el('autoVisaBtn').getAttribute('aria-pressed'), title: page.el('autoVisaBtn').title,
                state: page.el('autoVisaState').textContent };
            page.route('/api/auto-visa', { body: { armed: true, others: ['bob'] } });
            await page.run('loadAutoVisa')(13575);
            const armed = { auto: page.el('autoVisaBtn').textContent, pressed: page.el('autoVisaBtn').getAttribute('aria-pressed'),
                title: page.el('autoVisaBtn').title, state: page.el('autoVisaState').textContent };
            page.route('/api/auto-visa', { body: { armed: false, others: [], standingBy: 'alice' } });
            await page.run('loadAutoVisa')(13575);
            const standing = { auto: page.el('autoVisaBtn').textContent, pressed: page.el('autoVisaBtn').getAttribute('aria-pressed'),
                disabled: page.el('autoVisaBtn').disabled, state: page.el('autoVisaState').textContent };
            report({ noTicket, idle, armed, standing });
            """);

        assertThat(out.get("noTicket").get("group").asBoolean()).isTrue();
        assertThat(out.get("noTicket").get("line").asBoolean()).as("the one line instead of two dead buttons").isFalse();
        assertThat(Files.readString(PAGE, UTF_8)).contains("<span id=\"noTicket\" class=\"visa-group muted hidden\">JIRA "
            + "visa: the PR title names no IGNITE ticket.</span>");

        JsonNode idle = out.get("idle");
        assertThat(idle.get("group").asBoolean()).isFalse();
        assertThat(Files.readString(PAGE, UTF_8)).contains("<span class=\"muted\">JIRA visa:</span>")
            .contains("<button id=\"visaBtn\" class=\"act\" type=\"button\" title=\"Post the verdict as a comment to the JIRA "
                + "ticket now\">Post now</button>");
        assertThat(idle.get("postTitle").asText()).isEqualTo("Post the verdict as a comment to IGNITE-29049 now");
        assertThat(idle.get("auto").asText()).isEqualTo("Auto visa");
        assertThat(idle.get("pressed").asText()).isEqualTo("false");
        assertThat(idle.get("title").asText()).isEqualTo("Post the verdict to IGNITE-29049 once, when the next RunAll of "
            + "this PR finishes. Your JIRA token is kept encrypted until then.");
        assertThat(idle.get("state").asText()).isEmpty();

        JsonNode armed = out.get("armed");
        assertThat(armed.get("auto").asText()).isEqualTo("Auto visa ✓");
        assertThat(armed.get("pressed").asText()).isEqualTo("true");
        assertThat(armed.get("title").asText()).isEqualTo("Armed by you. Click to cancel it; this also removes your stored token.");
        assertThat(armed.get("state").asText()).isEqualTo("also armed by bob");

        JsonNode standing = out.get("standing");
        assertThat(standing.get("auto").asText()).isEqualTo("Auto visa");
        assertThat(standing.get("pressed").asText()).isEqualTo("true");
        assertThat(standing.get("disabled").asBoolean()).isTrue();
        assertThat(standing.get("state").asText()).isEqualTo("on in your ⚙");
    }

    /** While the PR's RunAll runs, the switch names that run, not "the next". */
    @Test
    void theAutoVisaNamesTheRunAllThatRunsNow() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'running', name: 'RunAll', mine: true,
                webUrl: 'https://ci2.example/build/9100', leftSec: 3900, pct: 40 }] });
            await page.load('?pr=13575');
            """ + TICKET + """
            report({ title: page.el('autoVisaBtn').title });
            """);

        assertThat(out.get("title").asText()).startsWith("Post the verdict to IGNITE-29049 once, when the RunAll that is "
            + "running now finishes.");
    }

    /** A RunAll that starts while the page is open: the next poll of the runs repaints the switch's "when". */
    @Test
    void theAutoVisaFollowsTheRunsAsTheyArePolled() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            """ + TICKET + """
            const idle = page.el('autoVisaBtn').title;
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'running', name: 'RunAll', mine: true,
                webUrl: 'https://ci2.example/build/9100', leftSec: 600, pct: 80 }] });
            await page.run('loadRuns')(13575);
            const running = page.el('autoVisaBtn').title;
            page.route('/api/runs', { body: [] });
            await page.run('loadRuns')(13575);
            report({ idle, running, finished: page.el('autoVisaBtn').title });
            """);

        assertThat(out.get("idle").asText()).contains("when the next RunAll of this PR finishes");
        assertThat(out.get("running").asText()).contains("when the RunAll that is running now finishes");
        assertThat(out.get("finished").asText()).contains("when the next RunAll of this PR finishes");
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
    void aVisaAnswersUnderTheHeadingWithACopyInTheStatusLine() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: true, github: false } });
            page.route('/api/jira-visa', { body: { url: 'https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=1' } }, 'POST');
            page.route('/api/auto-visa?pr=13575&issue', { body: { armed: true } }, 'POST');
            await page.load('?pr=13575');
            """ + TICKET + """
            await page.run('postVisa')();
            const posted = { note: page.el('visaNote').textContent, html: page.el('visaNote').innerHTML,
                hidden: page.el('visaNote').classList.contains('hidden'), status: page.el('status').textContent };
            await page.run('armAutoVisa')();
            const armed = { note: page.el('visaNote').textContent, pressed: page.el('autoVisaBtn').getAttribute('aria-pressed') };
            page.route('/api/jira-visa', { status: 502, body: { error: 'JIRA answered 502' } }, 'POST');
            await page.run('postVisa')();
            report({ posted, armed, failed: { note: page.el('visaNote').textContent,
                err: page.el('visaNote').classList.contains('err'), status: page.el('status').textContent,
                statusErr: page.el('status').classList.contains('err') } });
            """);

        JsonNode posted = out.get("posted");
        assertThat(posted.get("note").asText()).isEqualTo("Visa posted to IGNITE-29049");
        assertThat(posted.get("html").asText())
            .contains("href=\"https://issues.apache.org/jira/browse/IGNITE-29049?focusedCommentId=1\"",
                "title=\"Open the comment in JIRA\"");
        assertThat(posted.get("hidden").asBoolean()).isFalse();
        assertThat(posted.get("status").asText()).isEqualTo("Visa posted to IGNITE-29049");

        assertThat(out.get("armed").get("note").asText()).isEqualTo("Auto visa armed: the verdict goes to IGNITE-29049 "
            + "the next time a RunAll of this PR finishes.");
        assertThat(out.get("armed").get("pressed").asText()).isEqualTo("true");

        JsonNode failed = out.get("failed");
        assertThat(failed.get("note").asText()).isEqualTo("⚠ Visa not posted: JIRA answered 502");
        assertThat(failed.get("err").asBoolean()).isTrue();
        assertThat(failed.get("status").asText()).isEqualTo("⚠ Visa not posted: JIRA answered 502");
        assertThat(failed.get("statusErr").asBoolean()).isTrue();
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
            .isEqualTo("[\"PR #13575: 2 tests started failing. A rerun shows whether they are blockers.\"]");
        assertThat(watch.get("title").asText()).isEqualTo("⚠ started failing · #13575 — Ignite PR Checker");
        assertThat(unproven.get("shown").toString())
            .isEqualTo("[\"PR #13575: no blockers found, not proven clean. Open the page to see why.\"]");
        assertThat(unproven.get("title").asText()).isEqualTo("⚠ not proven clean · #13575 — Ignite PR Checker");
    }
}
