package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PR 13655's page showed 62 broken suites as 62 rows with their own Rerun, Rerun top and ai, 60 of them one ci2
 * glitch, and pushed the blockers five screens down; on PR 13583 the failed Build stood fifth among the suites it had
 * kept from running, and 137 of them filled the "never ran" banner. The page draws the groups the server makes of
 * them, from the verdict as the server writes it.
 */
class BrokenGroupsPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aFailedBuildLeadsAndTheSuitesItKeptFromRunningAreOneLine() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.buildFailed(false)));

        assertThat(out.get("broken").asText())
            .startsWith("Build failed — nothing else ran; fix the build, then /run-all> Build")
            .contains("non-zero exit code", "145 suites that need it did not run");
        assertThat(out.get("html").asText().split("class=\"suite-rerun\"", -1)).as("Rerun and Rerun top of the Build")
            .hasSize(3);
        assertThat(out.get("html").asText()).contains("data-suite=\"" + BrokenRuns.BUILD + "\"");
        assertThat(out.get("bannerHidden").asBoolean()).as("the 137 suites the Build kept from running").isTrue();
        assertThat(out.get("sectionRerunHidden").asBoolean()).isFalse();
        assertThat(out.get("caveat").asText()).isEqualTo("Nothing here was blamed on this PR, but this run can't prove "
            + "it is clean: Build failed — nothing else ran.");
    }

    @Test
    void aBuildThatFailedToCompileHasNothingToReRun() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.buildFailed(true)));

        assertThat(out.get("broken").asText()).contains("compilation error");
        assertThat(out.get("html").asText()).doesNotContain("class=\"suite-rerun\"");
        assertThat(out.get("sectionRerunHidden").asBoolean()).isTrue();
    }

    /** Before the suites that need it fail, a Build that failed to compile is a broken suite of its own. */
    @Test
    void aSuiteThatFailedToCompileHasNoRerun() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite(BrokenRuns.BUILD, 1L, "> Build", List.of("compilation error"), 0, 0)))));

        assertThat(out.get("broken").asText()).contains("> Build", "compilation error");
        assertThat(out.get("html").asText()).doesNotContain("class=\"suite-rerun\"");
        assertThat(out.get("sectionRerunHidden").asBoolean()).isTrue();
    }

    @Test
    void theArtifactGlitchIsOneRowWithItsSuitesFoldedAway() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.artifactsGlitch()));

        String broken = out.get("broken").asText();
        assertThat(broken).containsSubsequence("ci2 glitch: artifacts unavailable (60 suites)", "Rerun (60)",
            "Rerun top (60)", "a re-run usually gets them", "60 suites", "Suite 1",
            "non-zero exit code", "Rerun (2)", "2 suites", "PDS 5", "PDS 6",
            "Cache 5", "execution timeout — ran 12 of master's 35 tests", "Basic 3");
        assertThat(broken.split("ci2 glitch: artifacts unavailable", -1)).hasSize(2);
        assertThat(out.get("html").asText()).doesNotContain("<[Apache").contains("&lt;[Apache Ignite 2.x / Tests]");
        assertThat(out.get("caveat").asText()).endsWith("this run can't prove it is clean: 64 suite(s) have no reliable "
            + "result (60× ci2 glitch: artifacts unavailable; 2× non-zero exit code; execution timeout; …).");
    }

    /**
     * The Build failed, a re-run of it passed, and the suites it kept from running were all cancelled rather than
     * failed: no suite is broken, yet 137 never ran, and the page does not call the PR good to go.
     */
    @Test
    void aBuildThatPassedOnARerunStillShowsTheSuitesThatNeverRan() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.withBroken(BrokenRuns.buildFailed(false),
            List.of())));

        assertThat(out.get("cardHidden").asBoolean()).isFalse();
        assertThat(out.get("broken").asText()).startsWith("Build failed in this run and passed on a re-run since — 137 "
            + "suites that need it never ran; /run-all to run them");
        assertThat(out.get("noBlockersHidden").asBoolean()).isTrue();
    }

    /** A problem's text comes from the build log, which the PR's code can write to. */
    @Test
    void aSharedProblemIsDrawnAsText() throws Exception {
        List<String> problem = List.of("<img src=x onerror=alert(1)>");
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_Cache1", 1L, "Cache 1", problem, 0, 0),
            new BrokenSuite("IgniteTests24Java8_Cache2", 2L, "Cache 2", problem, 0, 0)))));

        assertThat(out.get("html").asText()).doesNotContain("<img").contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    /** The scenario: PR 13575's page showing {@code verdict}, as the server serializes it, and what it drew. */
    private static String page(AnalysisResult verdict) throws Exception {
        return PageScript.SIGNED_IN + "const SERVED = " + JSON.writeValueAsString(AnalyzeController.Served.of(verdict))
            + ";\n" + """
            page.route('/api/analyze', { body: Object.assign(SERVED, { prNumber: 13575 }) });
            await page.load('?pr=13575');
            report({ broken: page.el('brokenSuites').textContent, html: page.el('brokenSuites').innerHTML,
                bannerHidden: page.el('interruptedBanner').classList.contains('hidden'),
                sectionRerunHidden: page.el('brokenActs').classList.contains('hidden'),
                cardHidden: page.el('brokenCard').classList.contains('hidden'),
                noBlockersHidden: page.el('noBlockers').classList.contains('hidden'),
                caveat: page.el('verdictCaveat').textContent });
            """;
    }
}
