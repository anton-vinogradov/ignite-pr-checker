package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.Caveats.Glance;
import com.github.igniteprchecker.analysis.Caveats.Standing;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.web.AnalyzeController;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * "Is this verdict clean" was decided in four places: the PR list's tick, the page's head, and each of the two
 * markups of the visa and the PR comment, and the list once ticked PRs 13583, 13577 and 13389 whose pages and
 * comments said "No test blockers" over tests that started failing. One rule, {@link Caveats#standing}, now decides
 * for all of them, and the page gets it from the server with the caveats as the server words them.
 */
class OneCleanRuleTest {
    private static final String HEAD = "5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4";

    private static final String OLDER = "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567";

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    @Test
    void testsToWatchAreNotCleanAnywhere() {
        assertEverywhere(verdict(13583, List.of(), List.of(watch()), List.of()), 0, Standing.WATCH);
    }

    @Test
    void aCoveredRunOfTheHeadWithNothingFoundIsCleanEverywhere() {
        assertEverywhere(verdict(13461, List.of(), List.of(), List.of()), 0, Standing.CLEAN);
    }

    @Test
    void aBrokenSuiteLeavesTheVerdictUnprovenEverywhere() {
        assertEverywhere(verdict(13593, List.of(), List.of(), List.of(new BrokenSuite("Cache1", 9390100L, "Cache 1",
            List.of("Execution timeout"), 0, 0))), 0, Standing.UNPROVEN);
    }

    @Test
    void aCleanRunOfOlderCodeIsOldCodeEverywhere() {
        assertEverywhere(verdict(13636, List.of(), List.of(), List.of()), 28, Standing.OLD_CODE);
    }

    @Test
    void blockersComeFirstEverywhere() {
        assertEverywhere(verdict(13655, List.of(blocker()), List.of(watch()), List.of()), 3, Standing.BLOCKERS);
    }

    /** The page reads the standing and the caveats with the analysis, beside its usual fields. */
    @Test
    void theServedAnalysisCarriesItsStandingAndCaveats() {
        AnalysisResult r = verdict(13593, List.of(), List.of(), List.of(new BrokenSuite("Cache1", 9390100L, "Cache 1",
            List.of("Execution timeout"), 0, 0)));

        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        when(analyzer.analyze("t", 13593)).thenReturn(Optional.of(r));
        AnalyzeController served = new AnalyzeController(analyzer, mock(RunDeltaStore.class), mock(PendingCommits.class));

        JsonNode json = new ObjectMapper().valueToTree(served.analyze(13593, "t").getBody());

        assertThat(json.get("prNumber").asInt()).isEqualTo(13593);
        assertThat(json.get("buildId").asLong()).isEqualTo(9390000L);
        assertThat(json.get("standing").asText()).isEqualTo("UNPROVEN");
        assertThat(json.get("caveats")).hasSize(1);
        assertThat(json.get("caveats").get(0).asText())
            .isEqualTo("1 suite(s) have no reliable result (Execution timeout)");
        assertThat(json.has("result")).isFalse();
    }

    /**
     * A clean run of PR 13636 with 28 commits pushed since: /api/pending gives the page the sentence the PR comment
     * says. A compare GitHub failed counts as one commit, as in the visa.
     */
    @Test
    void commitsPushedSinceTheRunComeInTheServersWords() {
        AnalysisResult r = verdict(13636, List.of(), List.of(), List.of());
        PendingCommits pending = mock(PendingCommits.class);
        when(pending.since("t", 13636, r.buildId()))
            .thenReturn(new PendingCommits.Ahead(28, "0a1b2c3", "5be1c0d", OLDER, false),
                new PendingCommits.Ahead(-1, "0a1b2c3", "5be1c0d", OLDER, false));
        AnalyzeController served = new AnalyzeController(mock(BlockerAnalyzer.class), mock(RunDeltaStore.class),
            pending);

        Object caveat = served.pending(13636, r.buildId(), "t").get("caveat");

        assertThat(caveat).isEqualTo("28 commit(s) pushed since this run — it tested older code");
        assertThat(visas.composeMarkdown(13636, r, 28, HEAD)).contains("- " + caveat + "\n");
        assertThat(served.pending(13636, r.buildId(), "t"))
            .containsEntry("caveat", "1 commit(s) pushed since this run — it tested older code");
    }

    private void assertEverywhere(AnalysisResult r, int commitsAhead, Standing expected) {
        Integer ahead = commitsAhead == 0 ? null : commitsAhead;
        assertThat(Caveats.standing(r, ahead)).as("the rule").isEqualTo(expected);
        assertThat(Glance.of(r).against(ahead == null ? HEAD : OLDER)).as("the PR list").isEqualTo(expected);

        String md = visas.composeMarkdown(r.prNumber(), r, ahead, HEAD);
        String wiki = visas.compose(r.prNumber(), r, ahead, HEAD);
        assertThat(md.contains("✅ **No blockers**")).as("the PR comment is green").isEqualTo(expected == Standing.CLEAN);
        assertThat(wiki.contains("(/) *No blockers*")).as("the visa is green").isEqualTo(expected == Standing.CLEAN);
        assertThat(md.contains("No proven blocker yet")).as("the PR comment waits for a re-run")
            .isEqualTo(expected == Standing.WATCH);
        assertThat(wiki.contains("No proven blocker yet")).as("the visa waits for a re-run")
            .isEqualTo(expected == Standing.WATCH);
        boolean unproven = expected != Standing.CLEAN && expected != Standing.WATCH && expected != Standing.BLOCKERS;
        assertThat(md.contains("can't prove the PR is clean")).as("the PR comment can't prove it").isEqualTo(unproven);
        assertThat(wiki.contains("can't prove the PR is clean")).as("the visa can't prove it").isEqualTo(unproven);
    }

    private static AnalysisResult verdict(int pr, List<TestVerdict> blockers, List<TestVerdict> watch,
        List<BrokenSuite> broken) {
        return new AnalysisResult(pr, 9390000L, "pull/" + pr + "/head", System.currentTimeMillis(), blockers, watch,
            List.of(), broken, List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0,
            HEAD, 0);
    }

    private static TestVerdict blocker() {
        return new TestVerdict(5272433775095107011L, "org.apache.ignite.util.GridCommandHandlerTest.testState",
            "IgniteTests24Java8_ControlUtility", 9390050L, "Control Utility", "o1", true, false,
            "failed its only run on this branch; not seen failing in 100 master run(s)", "F", 1,
            List.of(TestVerdict.Doubt.ONE_RUN));
    }

    private static TestVerdict watch() {
        return new TestVerdict(5272433775095107012L, "org.apache.ignite.cache.CacheReconnectTest.testReconnect",
            "IgniteTests24Java8_Cache1", 9390051L, "Cache 1", "o2", false, true,
            "passed 2 earlier runs, failed the latest on new code", "PPF", 1, List.of(TestVerdict.Doubt.ONE_RUN));
    }
}
