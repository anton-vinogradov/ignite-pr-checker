package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.AbandonedTestsCheck;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.VisaService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A test class in no suite is never run by CI, and a clean verdict said nothing of it. When Ignite's own
 * abandoned-tests check of the PR's head names such classes, the PR comment and the JIRA visa say so in one line under
 * the verdict, which stays as it is.
 */
class NotInAnySuiteVerdictTest {
    private static final int PR = 13335;

    private static final String JOB = "https://github.com/apache/ignite/actions/runs/18001/job/51234567890";

    private final PrTestRuns prTests = mock(PrTestRuns.class);

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example", new GithubProperties("apache/ignite", null, 300), prTests);

    @Test
    void theCommentAndTheVisaNameTheClassesInNoSuite() {
        when(prTests.notInAnySuite(PR)).thenReturn(Optional.of(new AbandonedTestsCheck.Outcome(State.FAILED,
            "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b", JOB, null,
            List.of("org.apache.ignite.ssl.SslContextReloadTest", "org.apache.ignite.internal.ssl.SslRenewalTest"),
            true)));

        String md = visas.composeMarkdown(PR, clean(), null, null);
        String wiki = visas.compose(PR, clean(), null, null);

        assertThat(md).contains("✅ **No blockers** — nothing in this run looks caused by this PR.")
            .contains("\n\n⚠️ **2 test classes are in no test suite**, so CI never runs them: `SslContextReloadTest`, "
                + "`SslRenewalTest` ([Ignite's abandoned-tests check](" + JOB + ") on `9a8b7c6`). Add each to a "
                + "test suite or mark it `@Ignore`.\n\n<sub>");
        assertThat(wiki).contains("(/) *No blockers*")
            .contains("\n\n(!) *2 test classes are in no test suite*, so CI never runs them: {{SslContextReloadTest}}, "
                + "{{SslRenewalTest}} ([Ignite's abandoned-tests check|" + JOB + "] on {{9a8b7c6}}). Add each to a "
                + "test suite or mark it {{@Ignore}}.\n\n_What each label");
        assertThat(PrCommands.readsAsCommand(md)).isFalse();
    }

    @Test
    void oneClassIsTalkedOfAsOne() {
        when(prTests.notInAnySuite(PR)).thenReturn(Optional.of(new AbandonedTestsCheck.Outcome(State.FAILED,
            "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b", null, null,
            List.of("org.apache.ignite.ssl.SslContextReloadTest"), true)));

        assertThat(visas.composeMarkdown(PR, clean(), null, null)).contains("⚠️ **1 test class is in no test suite**, "
            + "so CI never runs it: `SslContextReloadTest` (Ignite's abandoned-tests check on `9a8b7c6`). Add it to a "
            + "test suite or mark it `@Ignore`.");
    }

    @Test
    void nothingIsAddedWhenTheCheckNamesNoClass() {
        when(prTests.notInAnySuite(PR)).thenReturn(Optional.empty());
        VisaService without = new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example");

        assertThat(visas.composeMarkdown(PR, clean(), null, null)).isEqualTo(without.composeMarkdown(PR, clean(),
            null, null));
        assertThat(visas.compose(PR, clean(), null, null)).isEqualTo(without.compose(PR, clean(), null, null));
    }

    private static AnalysisResult clean() {
        return new AnalysisResult(PR, 9389046L, "pull/13335/head", 0, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 7, false, 0, false, 0, 0, 0, 0, 0);
    }
}
