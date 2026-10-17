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
        assertThat(out.get("html").asText()).as("the section's pair re-runs the Build alone: the row has no twin")
            .doesNotContain("suite-rerun");
        assertThat(out.get("sectionSuites").toString()).isEqualTo("[\"" + BrokenRuns.BUILD + "\"]");
        assertThat(out.get("bannerHidden").asBoolean()).as("the 137 suites the Build kept from running").isTrue();
        assertThat(out.get("sectionRerunHidden").asBoolean()).isFalse();
        assertThat(out.get("caveat").asText()).isEqualTo("This run can't prove the PR is clean: Build failed — nothing "
            + "else ran.");
    }

    @Test
    void aBuildThatFailedToCompileHasNothingToReRun() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.buildFailed(true)));

        assertThat(out.get("broken").asText()).contains("compilation error");
        assertThat(out.get("html").asText()).doesNotContain("suite-rerun");
        assertThat(out.get("sectionRerunHidden").asBoolean()).isTrue();
    }

    /** Before the suites that need it fail, a Build that failed to compile is a broken suite of its own. */
    @Test
    void aSuiteThatFailedToCompileHasNoRerun() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite(BrokenRuns.BUILD, 1L, "> Build", List.of("compilation error"), 0, 0)))));

        assertThat(out.get("broken").asText()).contains("> Build", "compilation error");
        assertThat(out.get("html").asText()).doesNotContain("suite-rerun");
        assertThat(out.get("sectionRerunHidden").asBoolean()).isTrue();
    }

    @Test
    void theArtifactGlitchIsOneRowWithItsSuitesFoldedAway() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.artifactsGlitch()));

        String broken = out.get("broken").asText();
        assertThat(broken).containsSubsequence("ci2 glitch: artifacts unavailable (60 suites)", "Rerun 60 suites",
            "Rerun at top", "a re-run usually gets them", "60 suites", "Suite 1",
            "non-zero exit code", "Rerun 2 suites", "2 suites", "PDS 5", "PDS 6",
            "Cache 5", "execution timeout — ran 12 of master's 35 tests", "Basic 3");
        assertThat(broken.split("ci2 glitch: artifacts unavailable", -1)).hasSize(2);
        assertThat(out.get("html").asText()).doesNotContain("<[Apache").contains("&lt;[Apache Ignite 2.x / Tests]");
        assertThat(out.get("caveat").asText()).isEqualTo("This run can't prove the PR is clean: 64 suites have no "
            + "reliable result (60× ci2 glitch: artifacts unavailable; 2× non-zero exit code; execution timeout; …).");
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
        assertThat(out.get("head").asText()).isEqualTo("No blockers found — not proven clean");
    }

    /** TeamCity named only its count of the shortfall, so the server made it the suite's one problem: the row says it once. */
    @Test
    void aSuiteWhoseOnlyProblemIsItsShortfallSaysItOnce() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_Ml", 9392102L, "Machine Learning", List.of("ran 12 of master's 35 tests"),
                12, 35)))));

        String broken = out.get("broken").asText();
        assertThat(broken).contains("Machine Learning", "ran 12 of master's 35 tests");
        assertThat(broken.split("ran 12 of master's 35 tests", -1)).hasSize(2);
    }

    /**
     * TeamCity failed Machine Learning and Cache 5 only for their test counts, 12 of master's 35 and 30 of master's
     * 80: the group read "ran N of master's N tests", as if each had run all of master's tests.
     */
    @Test
    void suitesShortOnlyOfTestsShareALineWithoutNumbers() throws Exception {
        JsonNode out = PageScript.run("index.html", page(BrokenRuns.withBroken(BrokenRuns.artifactsGlitch(), List.of(
            new BrokenSuite("IgniteTests24Java8_Ml", 1L, "Machine Learning", List.of("ran 12 of master's 35 tests"), 12,
                35),
            new BrokenSuite("IgniteTests24Java8_Cache5", 2L, "Cache 5", List.of("ran 30 of master's 80 tests"), 30,
                80)))));

        assertThat(out.get("broken").asText())
            .containsSubsequence("ran fewer tests than on master", "2 suites", "Machine Learning",
                "ran 12 of master's 35 tests", "Cache 5", "ran 30 of master's 80 tests")
            .doesNotContain("ran N");
        assertThat(out.get("caveat").asText()).isEqualTo("This run can't prove the PR is clean: 2 suites have no "
            + "reliable result (2× ran fewer tests than on master).");
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
                head: page.el('verdictText').textContent,
                sectionSuites: page.run('[...sectActs.get("brokenActs").suites]'),
                caveat: page.el('verdictLines').textContent });
            """;
    }
}
