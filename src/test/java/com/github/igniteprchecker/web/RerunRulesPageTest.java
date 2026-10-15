package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * PR 13692's page had eleven Rerun buttons that looked the same: the card's pair, the "Not confirmed" pair under it
 * with the same two suites, and a pair on each suite row, with nothing to say which was the next step. A suite
 * re-run from the bottom of a long list answered in the grey line at the top, past the edge of the screen. Now a
 * section's pair says how many suites it re-runs, a pair that would re-run what the pair above it re-runs is left
 * out, one button is filled as the next step, and the answer stands under the buttons that were pressed.
 */
class RerunRulesPageTest {
    private static final Path PAGE = Path.of("src/main/resources/static/index.html");

    /** Gives a section's pair real buttons, which the harness's elements lack. */
    private static final String PAIRS = """
        const pairs = {};
        for (const id of ['brokenActs', 'blockerActs', 'oneRunActs', 'cancelledActs', 'shrunkActs']) {
            const rerun = page.el(id + '-rerun'), top = page.el(id + '-top');
            rerun.dataset.top = 'false';
            top.dataset.top = 'true';
            page.el(id).querySelectorAll = () => [rerun, top];
            pairs[id] = rerun;
        }
        const primary = () => Object.entries(pairs).filter(([, b]) => b.classList.contains('primary')).map(([id]) => id)
            .concat(page.el('pendingRerun').classList.contains('primary') ? ['pendingRerun'] : []);
        const once = (name, occ, suite) => Object.assign(blocker(name, occ), { suite, suiteBuildId: suite.length,
            suiteName: suite, branchRuns: 'F', codeRuns: 1, doubts: ['ONE_RUN'] });
        """;

    @Test
    void aSectionsPairSaysHowManySuitesItReRuns() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + PAIRS + """
            page.route('/api/analyze', { body: verdict({ blockers: [blocker('A.a', 'o1'),
                Object.assign(blocker('B.b', 'o2'), { suite: 'IgniteTests24Java8_Pds', suiteBuildId: 9003, suiteName: 'PDS' })] }) });
            await page.load('?pr=13575');
            report({ rerun: pairs.blockerActs.textContent, top: page.el('blockerActs-top').textContent,
                title: pairs.blockerActs.title });
            """);

        assertThat(out.get("rerun").asText()).isEqualTo("Rerun 2 suites");
        assertThat(out.get("top").asText()).isEqualTo("Rerun at top");
        assertThat(out.get("title").asText()).isEqualTo("Re-run the 2 suites of this section");
    }

    /** One suite ran fewer tests: the card's pair re-runs it, so its row has no pair; with two, each row has one. */
    @Test
    void aRowsPairIsLeftOutWhenThePairAboveReRunsJustThatSuite() throws Exception {
        String scenario = """
            page.route('/api/analyze', { body: verdict({ shrunkSuites: SHRUNK }) });
            await page.load('?pr=13575');
            report({ html: page.el('shrunkSuites').innerHTML });
            """;
        String one = "[{ suite: 'Cache1', suiteName: 'Cache 1', suiteBuildId: 9003, tests: 30, baseline: 35, dropPct: 14 }]";
        String two = "[{ suite: 'Cache1', suiteName: 'Cache 1', suiteBuildId: 9003, tests: 30, baseline: 35, dropPct: 14 },"
            + " { suite: 'Cache2', suiteName: 'Cache 2', suiteBuildId: 9004, tests: 30, baseline: 35, dropPct: 14 }]";

        JsonNode single = PageScript.run("index.html", PageScript.SIGNED_IN + "const SHRUNK = " + one + ";" + scenario);
        JsonNode pair = PageScript.run("index.html", PageScript.SIGNED_IN + "const SHRUNK = " + two + ";" + scenario);

        assertThat(single.get("html").asText()).doesNotContain("suite-rerun");
        assertThat(pair.get("html").asText())
            .contains("<button class=\"suite-rerun quiet\" type=\"button\" data-suite=\"Cache1\" data-top=\"false\" "
                + "title=\"Re-run this suite\">Rerun</button>")
            .contains("data-suite=\"Cache2\" data-top=\"true\" title=\"Re-run this suite at the top of the queue\">Rerun at top</button>");
    }

    /**
     * The same rule in the lists of tests: with one blocker the card's pair re-runs its only suite, so the row has no
     * pair; with two suites each row has its quiet pair; a row of Could not be checked keeps its pair, since no pair
     * stands above it.
     */
    @Test
    void aTestListsSuiteRowHasAQuietPairUnlessThePairAboveReRunsJustItsSuite() throws Exception {
        String scenario = """
            page.route('/api/analyze', { body: verdict(VERDICT) });
            await page.load('?pr=13575');
            report({ blockers: page.el('blockers').innerHTML, unverified: page.el('unverified').innerHTML,
                sectionSuites: page.run('[...sectActs.get("blockerActs").suites]') });
            """;
        String unverified = "{ unverified: [Object.assign(blocker('C.c', 'o3'), { blocker: false,"
            + " reason: 'could not verify (TeamCity error: 502)' })] }";
        String twoSuites = "{ blockers: [blocker('A.a', 'o1'), Object.assign(blocker('B.b', 'o2'),"
            + " { suite: 'IgniteTests24Java8_Pds', suiteBuildId: 9003, suiteName: 'PDS' })] }";
        String quietRerun = "<button class=\"suite-rerun quiet\" type=\"button\" data-suite=\"IgniteTests24Java8_Cache\" "
            + "data-top=\"false\" title=\"Re-run this suite\">Rerun</button>";

        JsonNode one = PageScript.run("index.html", PageScript.SIGNED_IN + "const VERDICT = " + unverified + ";" + scenario);
        JsonNode two = PageScript.run("index.html", PageScript.SIGNED_IN + "const VERDICT = " + twoSuites + ";" + scenario);

        assertThat(one.get("sectionSuites").toString()).isEqualTo("[\"IgniteTests24Java8_Cache\"]");
        assertThat(one.get("blockers").asText()).as("the card's pair re-runs this one suite").doesNotContain("suite-rerun");
        assertThat(one.get("unverified").asText()).as("no pair stands above Could not be checked").contains(quietRerun);
        assertThat(two.get("blockers").asText()).contains(quietRerun)
            .contains("data-suite=\"IgniteTests24Java8_Pds\" data-top=\"true\" title=\"Re-run this suite at the top of the queue\">"
                + "Rerun at top</button>")
            .doesNotContain("class=\"suite-rerun\"");
    }

    @Test
    void theOneFilledButtonIsTheNextStep() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + PAIRS + """
            const broken = [{ suite: 'Cache1', suiteName: 'Cache 1', suiteBuildId: 9003, problems: ['execution timeout'],
                tests: 0, baseline: 0 }];
            page.route('/api/analyze', { body: verdict({ blockers: [once('A.a', 'o1', 'Python'), blocker('B.b', 'o2')],
                brokenSuites: broken }) });
            await page.load('?pr=13575');
            const withBroken = primary();
            page.route('/api/analyze', { body: verdict({ computedAt: page.now(), blockers: [once('A.a', 'o1', 'Python'),
                blocker('B.b', 'o2')] }) });
            await page.run('analyze')(13575);
            const notConfirmed = primary();
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() + 1, blockers: [once('A.a', 'o1', 'Python'),
                once('A.b', 'o2', 'Python')] }) });
            await page.run('analyze')(13575);
            const allOnce = primary();
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() + 2 }) });
            await page.run('analyze')(13575);
            const confirmed = primary();
            page.route('/api/pending', { body: { pending: true, ahead: 2, builtSha: 'ac9f829', headSha: '5ae5733',
                rewritten: false } });
            await page.run('loadPending')(page.run('lastResult'));
            const olderCode = primary();
            page.route('/api/runs', { body: [{ buildId: 9100, runAll: true, state: 'queued', name: 'RunAll', mine: true,
                webUrl: 'https://ci2.example/build/9100', startSec: 600 }] });
            await page.tick(90000);
            const runAllQueued = primary();
            page.route('/api/runs', { body: [] });
            page.route('/api/pending', { body: { pending: false, checked: true } });
            await page.run('loadPending')(page.run('lastResult'));
            page.route('/api/analyze', { body: verdict({ computedAt: page.now() + 3, brokenSuites: broken }) });
            await page.run('analyze')(13575);
            await page.tick(90000);
            const brokenAgain = primary();
            page.route('/api/reruns', { body: [{ pr: 13575, buildId: 9200, buildTypeId: 'Cache1', state: 'queued',
                webUrl: 'https://ci2.example/build/9200' }] });
            await page.tick(90000);
            const alreadyQueued = primary();
            page.route('/api/reruns', { body: [] });
            page.route('/api/settling', { body: { phase: 'settling', wave: 1, of: 2, what: '1 suite' } });
            await page.tick(90000);
            report({ withBroken, notConfirmed, allOnce, confirmed, olderCode, runAllQueued, brokenAgain, alreadyQueued,
                settling: primary() });
            """);

        assertThat(out.get("withBroken").toString()).as("broken suites first").isEqualTo("[\"brokenActs\"]");
        assertThat(out.get("notConfirmed").toString()).isEqualTo("[\"oneRunActs\"]");
        assertThat(out.get("allOnce").toString()).as("every blocker on one run: the card's pair").isEqualTo("[\"blockerActs\"]");
        assertThat(out.get("confirmed").toString()).as("confirmed blockers: nothing to re-run").isEqualTo("[]");
        assertThat(out.get("olderCode").toString()).isEqualTo("[\"pendingRerun\"]");
        assertThat(out.get("runAllQueued").toString()).as("a RunAll is queued: wait").isEqualTo("[]");
        assertThat(out.get("brokenAgain").toString()).isEqualTo("[\"brokenActs\"]");
        assertThat(out.get("alreadyQueued").toString()).as("its suite is already queued: wait").isEqualTo("[]");
        assertThat(out.get("settling").toString()).as("auto re-runs go: wait").isEqualTo("[]");
    }

    /**
     * A group that is the whole section has no pair of its own; one that is part of it keeps its pair, and a suite that
     * is its own group keeps its row's pair, since the section's pair re-runs more than it.
     */
    @Test
    void aGroupsPairIsLeftOutWhenItIsTheSections() throws Exception {
        String suite = "const suite = (id, name) => ({ suite: id, suiteName: name, suiteBuildId: id.length,"
            + " problems: ['non-zero exit code'], tests: 0, baseline: 0 });";
        String scenario = """
            page.route('/api/analyze', { body: verdict({ brokenSuites: SUITES, brokenGroups: GROUPS }) });
            await page.load('?pr=13575');
            report({ html: page.el('brokenSuites').innerHTML });
            """;
        String whole = "const SUITES = [suite('Pds5', 'PDS 5'), suite('Pds6', 'PDS 6')];"
            + " const GROUPS = [{ kind: 'PROBLEM', title: 'non-zero exit code', suites: SUITES, cancelled: [], rerun: ['Pds5', 'Pds6'] }];";
        String part = "const SUITES = [suite('Pds5', 'PDS 5'), suite('Pds6', 'PDS 6'), suite('Cache5', 'Cache 5')];"
            + " const GROUPS = [{ kind: 'PROBLEM', title: 'non-zero exit code', suites: SUITES.slice(0, 2), cancelled: [], rerun: ['Pds5', 'Pds6'] },"
            + " { kind: 'PROBLEM', title: 'execution timeout', suites: [SUITES[2]], cancelled: [], rerun: ['Cache5'] }];";

        JsonNode wholeOut = PageScript.run("index.html", PageScript.SIGNED_IN + suite + whole + scenario);
        JsonNode partOut = PageScript.run("index.html", PageScript.SIGNED_IN + suite + part + scenario);

        assertThat(wholeOut.get("html").asText()).doesNotContain("group-rerun")
            .contains("data-suite=\"Pds5\"", "data-suite=\"Pds6\"");
        assertThat(partOut.get("html").asText()).contains(">Rerun 2 suites</button>")
            .contains("data-suite=\"Pds5\"", "data-suite=\"Pds6\"", "data-suite=\"Cache5\"");
    }

    @Test
    void theAnswerToARerunStandsUnderItsButtonsAndTheChipAppearsAtOnce() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/rerun-suites', { status: 502, body: { error: 'Bad Gateway' } }, 'POST');
            await page.load('?pr=13575');
            const note = page.el('blockerActsNote');
            await page.run('rerunSuites')(['Cache'], false, page.el('btn'), note);
            const failed = { note: note.textContent, err: note.classList.contains('err'), hidden: note.classList.contains('hidden'),
                status: page.el('status').textContent };
            page.route('/api/rerun-suites', { body: { triggered: [{ buildId: 777, btId: 'Cache',
                webUrl: 'https://ci2.example/build/777' }] } }, 'POST');
            await page.run('rerunSuites')(['Cache'], false, page.el('btn'), note);
            const queued = { note: note.textContent, hidden: note.classList.contains('hidden'),
                status: page.el('status').innerHTML, chip: page.run('lastRuns').filter(b => b.buildId === 777) };
            page.route('/api/rerun-suite', { body: { triggered: [{ buildId: 778, webUrl: 'https://ci2.example/build/778',
                belowTop: true, topRefusal: 403 }] } }, 'POST');
            await page.run('rerunSuite')('Query', true, page.el('rowBtn'));
            report({ failed, queued, row: page.run('lastRuns').filter(b => b.buildId === 778),
                rowStatus: page.el('status').textContent });
            """);

        JsonNode failed = out.get("failed");
        assertThat(failed.get("note").asText()).isEqualTo("⚠ Not queued. The service may be restarting. Try again in a moment.");
        assertThat(failed.get("err").asBoolean()).isTrue();
        assertThat(failed.get("hidden").asBoolean()).isFalse();
        assertThat(failed.get("status").asText()).isEqualTo("⚠ Not queued. The service may be restarting. Try again in a moment.");

        JsonNode queued = out.get("queued");
        assertThat(queued.get("hidden").asBoolean()).as("nothing to say under the buttons").isTrue();
        assertThat(queued.get("status").asText()).isEqualTo("Queued <a href=\"https://ci2.example/build/777\" target=\"_self\" "
            + "rel=\"noopener\" title=\"Open this build in TeamCity\">#777</a>.");
        assertThat(queued.get("chip").toString()).isEqualTo("[{\"buildId\":777,\"btId\":\"Cache\",\"state\":\"queued\","
            + "\"webUrl\":\"https://ci2.example/build/777\",\"pct\":-1}]");

        assertThat(out.get("row").get(0).get("btId").asText()).as("a row's answer names no build type").isEqualTo("Query");
        assertThat(out.get("rowStatus").asText())
            .isEqualTo("Queued #778, at the end of the queue: ci2 did not let your account move it to the top.");
    }

    @Test
    void theCardsSayWhatTheyMeanInOneLineAndNotBlamedKeepsItsPairInside() throws IOException {
        String page = Files.readString(PAGE, UTF_8).replaceAll("\\s+", " ");

        assertThat(page).contains("Rerun these first: the verdict above can't see their tests. A compilation error needs a "
            + "code fix instead, so it has no Rerun.")
            .contains("Tests that did not run cannot fail. Fine if this PR removes tests. If not, compare the test list with "
                + "master and rerun.")
            .contains("Not blockers yet: there are too few runs of this code to tell a break from a flake. Rerun their suites: "
                + "if they keep failing, they become blockers; if they pass, they drop out.")
            .contains("These tests failed, but TeamCity errors stopped the check. Not blockers. The checker tries again by itself.")
            .contains("None of their failures is blamed on this PR (see Not blamed on this PR), so the results of the tests "
                + "they ran stand. Nothing to do.")
            .doesNotContain("Didn't complete a reliable run", "the passes above them", "How the test classes this PR adds or "
                + "changes ran in this RunAll.</p>", "Filtered out");
        assertThat(page).containsPattern("<summary>Not blamed on this PR <span id=\"filteredCount\"></span> <a class=\"help\""
            + "[^>]*>\\?</a></summary> <p class=\"muted filtered-line\">Nothing to do for this PR. Rerun only to double-check "
            + "the flaky ones. <span class=\"sect-act hidden\" id=\"filteredActs\">");
        assertThat(page).containsPattern("<a class=\"help\" [^>]*title=\"([^\"]+)\" aria-label=\"\\1\"");
    }
}
