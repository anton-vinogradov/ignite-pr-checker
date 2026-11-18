package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.MasterSuites;
import com.github.igniteprchecker.analysis.MasterSuites.OnMaster;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The comment of PR 13860 took nine lines to say that three suites gave no result: as a caveat, as a list of broken
 * suites and as a verdict that could not call the PR clean and asked to "handle what is listed above". All three end
 * the same way on master, which it did not say. The verdict tells each suite once, with how it does on master, and
 * what is left for the author to do.
 */
class MasterInVerdictTest {
    private static final int PR = 13860;

    private static final String SHA = "1f16ac0a3d5b8c0e1f2a4b6c8d0e2f4a6b8c0d2e";

    private static final List<String> TIMEOUT = List.of("TC_EXECUTION_TIMEOUT", "TC_EXIT_CODE");

    private static final BrokenSuite NODE = new BrokenSuite("ThinClientNodeJs", 9505101L, "Thin client: Node.js",
        List.of("execution timeout", "non-zero exit code"), 0, 0, TIMEOUT, null);

    private static final BrokenSuite DUCKTESTS = new BrokenSuite("CheckCodeStyleDucktests", 9505102L,
        "[Check Code Style Ducktests]", List.of("execution timeout", "non-zero exit code"), 0, 0, TIMEOUT, null);

    private static final BrokenSuite PHP = new BrokenSuite("ThinClientPhp", 9505103L, "Thin client: PHP",
        List.of("non-zero exit code"), 0, 0, List.of("TC_EXIT_CODE"), null);

    private static final BrokenSuite CACHE = new BrokenSuite("Cache5", 9505104L, "Cache 5", List.of("execution timeout"),
        33, 67, List.of("TC_EXECUTION_TIMEOUT"), null);

    private final MasterSuites masters = mock(MasterSuites.class);

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example", new GithubProperties(null, null, null), null, masters);

    @BeforeEach
    void theThinClientsBreakOnMasterAndCache5DoesNot() {
        when(masters.onMaster(any(), any())).thenReturn(OnMaster.SAME);
        when(masters.onMaster(eq("Cache5"), any())).thenReturn(OnMaster.FINE);
    }

    @Test
    void suitesBrokenAsOnMasterLeaveNothingToDo() {
        AnalysisResult r = verdict(List.of(), List.of(NODE, DUCKTESTS, PHP));

        assertThat(visas.composeMarkdown(PR, r, null, SHA)).isEqualTo("""
            🔎 **No blockers found** · [full verdict](https://checker.example/ignite?pr=13860)

            3 suites gave no result, same as on master:
            - [Thin client: Node.js](https://ci2.example/build/9505101) — execution timeout · non-zero exit code
            - [\\[Check Code Style Ducktests\\]](https://ci2.example/build/9505102) — execution timeout · non-zero exit code
            - [Thin client: PHP](https://ci2.example/build/9505103) — non-zero exit code

            **Next:** nothing to do for this PR.

            <sub>RunAll [9505015](https://ci2.example/build/9505015) · \
            [1f16ac0](https://github.com/apache/ignite/pull/13860/commits/%s) · [glossary](%s)</sub>"""
            .formatted(SHA, VisaService.GLOSSARY));
        assertThat(visas.compose(PR, r, null, SHA))
            .startsWith("(?) *No blockers found* — [Ignite PR Checker|https://checker.example/ignite?pr=13860] verdict "
                + "for PR 13860\n\n3 suites gave no result, same as on master:\n"
                + "- [Thin client: Node.js|https://ci2.example/build/9505101] — execution timeout · non-zero exit code\n")
            .contains("\n\n*Next:* nothing to do for this PR.\n\n_RunAll [9505015|https://ci2.example/build/9505015] · ");
    }

    /** A suite that is fine on master is the one to look at: it comes first, and the rest are one line. */
    @Test
    void aSuiteThatIsFineOnMasterIsTheOneToReRun() {
        assertThat(visas.composeMarkdown(PR, verdict(List.of(), List.of(PHP, CACHE)), null, null)).startsWith("""
            ⚠️ **No blockers found, 1 suite needs a re-run** · [full verdict](https://checker.example/ignite?pr=13860)

            - [Cache 5](https://ci2.example/build/9505104) — execution timeout, ran 33 of master's 67 tests, fine on master

            Also without a result, same as on master: Thin client: PHP.

            **Next:** re-run Cache 5. If it ends the same way again, look for the cause in this PR.

            <sub>RunAll """);
    }

    /** Until master's runs of a suite are read the verdict says nothing of master, and blames nothing on the PR. */
    @Test
    void aSuiteWhoseMasterRunsAreNotReadSaysNothingOfMaster() {
        when(masters.onMaster(eq("Cache5"), any())).thenReturn(OnMaster.UNKNOWN);

        assertThat(visas.composeMarkdown(PR, verdict(List.of(), List.of(CACHE)), null, null))
            .startsWith("⚠️ **No blockers found, 1 suite needs a re-run** · ")
            .contains("- [Cache 5](https://ci2.example/build/9505104) — execution timeout, ran 33 of master's 67 tests\n\n"
                + "**Next:** re-run Cache 5.\n\n")
            .doesNotContain("master:", "on master");
    }

    @Test
    void blockersComeFirstAndTheSuitesAfterThem() {
        TestVerdict blocker = new TestVerdict(1L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9505105L, "Cache 1",
            "o1", true, false, "not seen failing in 100 master runs", "FF", 2);

        assertThat(visas.composeMarkdown(PR, verdict(List.of(blocker), List.of(PHP)), null, null))
            .startsWith("❌ **1 blocker in 1 suite** · [full verdict](https://checker.example/ignite?pr=13860)\n\n"
                + "- Cache 1 · `CacheTest.testPut` — failed 2 of 2 runs of this code")
            .contains("\n\n1 suite gave no result, same as on master:\n- [Thin client: PHP](https://ci2.example/build/"
                + "9505103) — non-zero exit code\n\n**Next:** fix the blockers, push, and run RunAll again");
    }

    /** No re-run gets past a compilation error: the same code fails the same way. */
    @Test
    void aSuiteThatFailsToCompileIsNotToReRun() {
        BrokenSuite calcite = new BrokenSuite("Calcite", 9505106L, "Calcite SQL", List.of("compilation error"), 0, 0,
            List.of("TC_COMPILATION_ERROR"), null);
        when(masters.onMaster(eq("Calcite"), any())).thenReturn(OnMaster.FINE);

        assertThat(visas.composeMarkdown(PR, verdict(List.of(), List.of(calcite)), null, null))
            .startsWith("⚠️ **No blockers found, 1 suite gave no result** · ")
            .contains("- [Calcite SQL](https://ci2.example/build/9505106) — compilation error, fine on master\n\n"
                + "**Next:** fix what fails to compile.\n\n");
    }

    /** What matters of a run the PR moved past is that it is not the PR's code: its verdict comes second. */
    @Test
    void aVerdictOfOlderCodeSaysSoFirst() {
        assertThat(visas.composeMarkdown(PR, verdict(List.of(), List.of(PHP)), 2, null)).startsWith("""
            ⏩ **Outdated: 2 commits pushed since this run** · [full verdict](https://checker.example/ignite?pr=13860)

            Of the code it tested: No blockers found.

            1 suite gave no result, same as on master:
            - [Thin client: PHP](https://ci2.example/build/9505103) — non-zero exit code

            **Next:** run RunAll on the current head.

            <sub>RunAll """);
        assertThat(visas.compose(PR, verdict(List.of(), List.of()), 1, null))
            .startsWith("(!) *Outdated: 1 commit pushed since this run* — ")
            .contains("\n\nOf the code it tested: No blockers.\n\n*Next:* run RunAll on the current head.\n\n");
    }

    @Test
    void aRunStillGoingAsksToWait() {
        AnalysisResult going = new AnalysisResult(PR, 9505015L, "pull/13860/head", 1, List.of(), List.of(), List.of(),
            List.of(), List.of(), 80, 0, false, 0, true, 9505015L, 0, 0, 0, 0);

        assertThat(visas.composeMarkdown(PR, going, null, null))
            .startsWith("🔎 **No blockers so far, this run is still going** · [full verdict](")
            .contains("\n\n**Next:** wait for this run to finish.\n\n<sub>RunAll ");
    }

    private static AnalysisResult verdict(List<TestVerdict> blockers, List<BrokenSuite> broken) {
        return new AnalysisResult(PR, 9505015L, "pull/13860/head", 1, blockers, List.of(), List.of(), broken, List.of(),
            149, 0, false, 0, false, 0, 0, 0, 0, 0);
    }
}
