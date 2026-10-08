package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The PR comment and the JIRA visa say "1 run", "watch", "pre-existing", "broken" and "can't prove the PR is clean",
 * and nothing in them said where those words are explained. Whatever a verdict says, its last line now links the
 * verdict glossary.
 */
class VerdictFooterTest {
    private static final int PR = 13575;

    private static final String GLOSSARY =
        "https://github.com/anton-vinogradov/ignite-pr-checker/blob/main/docs/features.md#verdict-glossary";

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    @Test
    void aCleanVerdictLinksTheGlossary() {
        assertEndsWithTheLink(PR, result(List.of()));
    }

    @Test
    void aVerdictWithBlockersLinksTheGlossaryAfterItsTagLegend() {
        TestVerdict blocker = new TestVerdict(1L, "org.apache.ignite.ClientReconnectTest.testReconnect",
            "IgniteTests24Java8_Cache", 9002L, "Cache", null, true, false, "failed the only run on this branch", "F", 1,
            List.of(TestVerdict.Doubt.ONE_RUN));

        assertEndsWithTheLink(PR, result(List.of(blocker)));
        assertThat(visas.composeMarkdown(PR, result(List.of(blocker))))
            .contains("</sub>\n\n<sub>What each label means");
    }

    /** A failed Build is the whole verdict: nothing else ran, and the verdict stops right after it. */
    @Test
    void aFailedBuildLinksTheGlossaryToo() {
        assertEndsWithTheLink(BrokenRuns.PR_13583, BrokenRuns.buildFailed(false));
    }

    private void assertEndsWithTheLink(int pr, AnalysisResult r) {
        assertThat(visas.composeMarkdown(pr, r))
            .endsWith("\n\n<sub>What each label means and what to do: [verdict glossary](" + GLOSSARY + ").</sub>")
            .containsOnlyOnce(GLOSSARY);
        assertThat(visas.compose(pr, r))
            .endsWith("\n\n_What each label means and what to do: [verdict glossary|" + GLOSSARY + "]._")
            .containsOnlyOnce(GLOSSARY);
    }

    private static AnalysisResult result(List<TestVerdict> blockers) {
        return new AnalysisResult(PR, 9001L, "pull/13575/head", System.currentTimeMillis(), blockers, List.of(),
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
