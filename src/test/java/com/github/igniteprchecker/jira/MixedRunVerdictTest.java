package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.RunRef;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.web.AnalyzeController;
import com.github.igniteprchecker.web.PrHeads;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * apache/ignite#13709 had RunAll 9425992 on c577b18 and Control Utility 5 re-run on the PR's head 9b02561, 3 commits
 * later. The PR comment and the visa of such a verdict say in one phrase which suites ran on which commit, in place of
 * "3 commits pushed since this run", and do not call the PR clean.
 */
class MixedRunVerdictTest {
    private static final String RUN_ALL_COMMIT = "c577b18e2f0d4a6b8c1d3e5f7a9b0c2d4e6f8a1b";

    private static final String HEAD = "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a";

    private static final String MIXED = "mixed run — Control Utility 5 re-ran on 9b02561, the rest of the RunAll ran on "
        + "c577b18; only a RunAll of the PR's head can prove it clean";

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    @Test
    void theCommentAndTheVisaSayItIsAMixedRun() {
        AnalysisResult r = mixed();

        String md = visas.composeMarkdown(13709, r, 3, RUN_ALL_COMMIT);
        String wiki = visas.compose(13709, r, 3, RUN_ALL_COMMIT);

        assertThat(md).contains("- " + MIXED + "\n")
            .contains("🔎 **No blockers found — but the run above can't prove the PR is clean.**")
            .doesNotContain("pushed since this run").doesNotContain("✅ **No blockers**");
        assertThat(wiki).contains("- " + MIXED + "\n")
            .contains("(?) *No blockers found — but the run above can't prove the PR is clean.*")
            .doesNotContain("pushed since this run").doesNotContain("(/) *No blockers*");
    }

    /** A visa of the same commit that was not a mixed run is not repeated by this one, nor this one by the next. */
    @Test
    void aMixedRunIsAnotherVerdictForTheVisa() {
        AnalysisResult plain = new AnalysisResult(13709, 9425992L, "pull/13709/head", 1, List.of(), List.of(),
            List.of(), List.of(), List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0,
            RUN_ALL_COMMIT, 0);

        assertThat(StandingVisas.verdictKey(mixed())).isNotEqualTo(StandingVisas.verdictKey(plain));
        assertThat(Caveats.keyOf(mixed())).containsExactly(
            "mixed run — suites re-ran on other commits than the rest of the RunAll");
    }

    /** The page gets the standing, and words the mixed run itself in its line above the verdict. */
    @Test
    void thePageGetsTheStandingWithoutTheCaveatItWordsItself() {
        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        when(analyzer.analyze("t", 13709)).thenReturn(Optional.of(mixed()));
        AnalyzeController served = new AnalyzeController(analyzer, mock(RunDeltaStore.class),
            mock(PendingCommits.class), mock(PrHeads.class));

        JsonNode json = Jackson2Defaults.mapper().valueToTree(served.analyze(13709, "t").getBody());

        assertThat(json.get("standing").asText()).isEqualTo("UNPROVEN");
        assertThat(json.get("caveats")).isEmpty();
        assertThat(json.get("otherCodeRuns").get(0).get("suiteName").asText()).isEqualTo("Control Utility 5");
        assertThat(json.get("otherCodeRuns").get(0).get("revision").asText()).isEqualTo(HEAD);
    }

    private static AnalysisResult mixed() {
        return new AnalysisResult(13709, 9425992L, "pull/13709/head", 1, List.of(), List.of(), List.of(), List.of(),
            List.of(), 148, 1, false, 0, false, 0, 0, 0, 1, 0, List.of(), List.of(), List.of(), 0, RUN_ALL_COMMIT, 0,
            List.of(new RunRef("IgniteTests24Java8_ControlUtility5", "Control Utility 5", 9426316L, HEAD)), List.of());
    }
}
