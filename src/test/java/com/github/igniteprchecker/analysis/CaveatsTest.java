package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.VisaService;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaveatsTest {
    private static AnalysisResult result(List<BrokenSuite> broken, List<ShrunkSuite> shrunk,
        boolean interrupted, int canceled, boolean live) {
        return new AnalysisResult(13434, 9253366, "pull/13434/head", System.currentTimeMillis(),
            List.of(), List.of(), List.of(), broken, shrunk, 147, 0, interrupted, canceled, live, 0, 0, 0, 0, 0);
    }

    private static AnalysisResult covered() {
        return result(List.of(), List.of(), false, 0, false);
    }

    @Test
    void aRunThatCoveredEverythingProvesTheVerdict() {
        assertThat(Caveats.of(covered(), null)).isEmpty();
        assertThat(Caveats.proven(covered())).isTrue();
    }

    @Test
    void anInterruptedRunCannotProveAnything() {
        AnalysisResult r = result(List.of(), List.of(), true, 118, false);

        assertThat(Caveats.proven(r)).isFalse();
        assertThat(Caveats.of(r, null)).singleElement().asString().contains("118 suites never ran");
    }

    @Test
    void brokenAndShrunkSuitesAreEachAReason() {
        AnalysisResult r = result(List.of(new BrokenSuite("Cache_1", 1L, "Cache 1", List.of("timeout"), 0, 0)),
            List.of(new ShrunkSuite("Cache_2", "Cache 2", 2L, 57, 439, 87)), false, 0, false);

        assertThat(Caveats.of(r, null)).hasSize(2);
    }

    @Test
    void commitsPushedSinceTheRunSupersedeIt() {
        assertThat(Caveats.of(covered(), 3)).singleElement().asString().contains("3 commits pushed");
        assertThat(Caveats.of(covered(), 0)).isEmpty(); // the head hasn't moved
    }

    /**
     * One suite never ran, one broke, one ran fewer tests, one failed test went unchecked, and a newer run goes:
     * each caveat reads in the singular. The key a visa is deduplicated by keeps the words of the first release that
     * posted visas under such keys, so the visa that release posted still matches and no second one goes out: the
     * caveat says "fewer", and the key keeps "far fewer".
     */
    @Test
    void theKeyKeepsTheWordsOfTheFirstVisasWhileTheShownWordsChange() {
        AnalysisResult r = new AnalysisResult(13434, 9253366, "pull/13434/head", System.currentTimeMillis(),
            List.of(), List.of(), List.of(), List.of(new BrokenSuite("Cache_1", 1L, "Cache 1", List.of("timeout"), 0, 0)),
            List.of(new ShrunkSuite("Cache_2", "Cache 2", 2L, 57, 439, 87)), 147, 0, true, 1, true, 0, 0, 0, 0, 0,
            List.of(), List.of(), List.of(new TestVerdict(8L, "TestB", "SuiteX", 200L, "Suite X", "302", false, false,
                "could not verify (TeamCity error: 502 Bad Gateway)", "", 0)), 0);

        assertThat(Caveats.of(r, null)).containsExactly(
            "the RunAll was interrupted — 1 suite never ran",
            "1 suite has no reliable result (timeout)",
            "1 suite ran fewer tests than the same suite on master",
            "1 failed test could not be checked (TeamCity errors)",
            "a newer run is still going — its unfinished suites can still fail");
        assertThat(Caveats.keyOf(r)).containsExactly(
            "the RunAll was interrupted — 1 suite(s) never ran",
            "1 suite(s) have no reliable result (compilation error, timeout, crash)",
            "1 suite(s) ran far fewer tests than the same suites on master",
            "1 failed test(s) could not be checked (TeamCity errors)",
            "a newer run is still going — its unfinished suites can still fail");
        assertThat(Caveats.pushedSince(1)).isEqualTo("1 commit pushed since this run — it tested older code");
    }

    @Test
    void anUnprovenVerdictNeverReadsAsAGreenTick() {
        VisaService visas = new VisaService(new TeamcityProperties("https://ci2/"), "https://checker");

        String clean = visas.composeMarkdown(13434, covered(), null);
        String unproven = visas.composeMarkdown(13434, result(List.of(), List.of(), true, 118, false), null);
        String stale = visas.composeMarkdown(13434, covered(), 2);

        assertThat(clean).contains("✅ **No blockers**");
        assertThat(unproven).doesNotContain("✅").contains("can't prove the PR is clean", "118 suites never ran");
        assertThat(stale).doesNotContain("✅").contains("2 commits pushed since this run");
    }
}
