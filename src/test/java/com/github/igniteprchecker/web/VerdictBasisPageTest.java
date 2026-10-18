package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * On prod all 31 blockers of five PRs rested on one run, yet the page drew blockers and tests to watch without
 * their reasons, and the AI prompt called each one "clean on master, failing consistently on this branch". On PR
 * 13566 such blockers stayed for two weeks. The page now says what each one rests on, and lists the blockers that
 * failed once apart, with their suites to re-run.
 */
class VerdictBasisPageTest {
    private static final String VERDICT = """
        function test(name, occ, over) {
            return Object.assign(blocker(name, occ), over);
        }
        page.route('/api/analyze', { body: verdict({
            blockers: [
                test('ClientReconnectTest.testReconnect', 'occ-1', { reason: 'not seen failing in 85 master runs on '
                    + 'JDK 17; failed all 3 runs on this branch', doubts: [] }),
                test('GridCommandHandlerTest.testCacheIdle', 'occ-2', { suite: 'IgniteTests24Java8_ControlUtility',
                    suiteBuildId: 9003, suiteName: 'Control Utility', branchRuns: 'F', codeRuns: 1,
                    reason: 'no master history on JDK 17 (can\\'t prove pre-existing); failed the only run on this '
                        + 'branch',
                    doubts: ['ONE_RUN', 'NO_MASTER_HISTORY'] }),
            ],
            watch: [test('TxRecoveryTest.testRecovery', 'occ-3', { blocker: false, watch: true, branchRuns: 'PPF',
                codeRuns: 1, reason: 'failed the only run on commit 5be1c0d; the 2 earlier runs were on other code',
                doubts: ['ONE_RUN'] })],
        }) });
        """;

    @Test
    void blockersAndTestsToWatchSayWhatTheyRestOn() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + VERDICT + """
            await page.load('?pr=13575');
            report({ blockers: page.el('blockers').textContent, oneRun: page.el('oneRunBlockers').textContent,
                watch: page.el('watch').textContent });
            """);

        assertThat(out.get("blockers").asText())
            .contains("not seen failing in 85 master runs on JDK 17; failed all 3 runs on this branch")
            .doesNotContain("testCacheIdle")
            .doesNotContain("1 run");
        assertThat(out.get("oneRun").asText()).as("the heading says 1 run for every test there")
            .contains("testCacheIdle", "no master history on JDK 17 (can't prove pre-existing)", "new test / no master history")
            .doesNotContain("failed the only run on this branch", "1 run");
        assertThat(out.get("watch").asText()).contains("failed the only run on commit 5be1c0d", "1 run");
    }

    /** A watch from a first failure on new code ran on the branch before: two passes on older code here. */
    @Test
    void theOneRunTagSaysWhatHoldsForAWatchToo() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + VERDICT + """
            await page.load('?pr=13575');
            report({ watch: page.el('watch').innerHTML, oneRun: page.el('oneRunBlockers').innerHTML });
            """);

        String tag = "title=\"The test ran once on this code and failed. A re-run of its suite confirms or clears the test&#39;s verdict.\">"
            + "1 run</span>";
        assertThat(out.get("watch").asText()).contains(tag).doesNotContain("the only time it ran");
        assertThat(out.get("oneRun").asText()).as("Not confirmed: 1 run says it once").doesNotContain(tag);
    }

    @Test
    void blockersFromOneRunAreAGroupWithItsSuitesToReRun() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + VERDICT + """
            await page.load('?pr=13575');
            report({ shown: !page.el('oneRunGroup').classList.contains('hidden'),
                count: page.el('oneRunCount').textContent,
                acts: !page.el('oneRunActs').classList.contains('hidden'),
                total: page.el('verdictText').textContent });
            """);

        assertThat(out.get("shown").asBoolean()).isTrue();
        assertThat(out.get("count").asText()).isEqualTo("(1 suite, 1 test)");
        assertThat(out.get("acts").asBoolean()).isTrue();
        assertThat(out.get("total").asText()).as("still counted as blockers")
            .isEqualTo("2 blockers in 2 suites, 1 of them on 1 run");
    }

    @Test
    void noGroupWhenEveryBlockerHasMoreThanOneRun() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            report({ shown: !page.el('oneRunGroup').classList.contains('hidden') });
            """);

        assertThat(out.get("shown").asBoolean()).isFalse();
    }

    @Test
    void theAiPromptGivesTheBlockersOwnReason() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + VERDICT + """
            await page.load('?pr=13575');
            report({ html: page.el('oneRunBlockers').innerHTML });
            """);

        assertThat(out.get("html").asText())
            .contains("data-verdict=\"BLOCKER — no master history on JDK 17 (can&#39;t prove pre-existing); "
                + "failed the only run on this branch\"")
            .doesNotContain("failing consistently");
    }
}
