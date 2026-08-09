package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.VisaService;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaveatsTest {
    private static AnalysisResult result(List<BrokenSuite> broken, List<ShrunkSuite> shrunk,
        boolean interrupted, int canceled, boolean live) {
        return new AnalysisResult(13434, 9253366, "pull/13434/head", System.currentTimeMillis(),
            List.of(), List.of(), List.of(), broken, shrunk, 147, 0, interrupted, canceled, live, 0, 0, 0, 0);
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
        assertThat(Caveats.of(r, null)).singleElement().asString().contains("118 suite(s) never ran");
    }

    @Test
    void brokenAndShrunkSuitesAreEachAReason() {
        AnalysisResult r = result(List.of(new BrokenSuite("Cache_1", 1L, "Cache 1", List.of("timeout"), 0, 0)),
            List.of(new ShrunkSuite("Cache_2", "Cache 2", 2L, 57, 439, 87)), false, 0, false);

        assertThat(Caveats.of(r, null)).hasSize(2);
    }

    @Test
    void commitsPushedSinceTheRunSupersedeIt() {
        assertThat(Caveats.of(covered(), 3)).singleElement().asString().contains("3 commit(s) pushed");
        assertThat(Caveats.of(covered(), 0)).isEmpty(); // the head hasn't moved
    }

    @Test
    void anUnprovenVerdictNeverReadsAsAGreenTick() {
        VisaService visas = new VisaService(new TeamcityProperties("https://ci2/"), "https://checker");

        String clean = visas.composeMarkdown(13434, covered(), null);
        String unproven = visas.composeMarkdown(13434, result(List.of(), List.of(), true, 118, false), null);
        String stale = visas.composeMarkdown(13434, covered(), 2);

        assertThat(clean).contains("✅ **No blockers**");
        assertThat(unproven).doesNotContain("✅").contains("can't prove the PR is clean", "118 suite(s) never ran");
        assertThat(stale).doesNotContain("✅").contains("2 commit(s) pushed since this run");
    }
}
