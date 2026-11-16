package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/** Verdict help explains the current result on the page instead of sending readers to source documentation. */
class VerdictHelpPageTest {
    /**
     * The help follows the verdict and gives a next action. Given a result, when its card is rendered, the tooltip
     * explains that result rather than showing the generic name of the glossary.
     */
    @ParameterizedTest
    @CsvSource({
        "BLOCKERS, These tests most likely failed because of this PR., fix confirmed blockers",
        "CLEAN, A run of the PR's current code found no failures blamed on it., No fixes or re-runs are needed",
        "WATCH, Tests started failing on this run's code, Re-run the affected suites",
        "CHECKING, No blockers were found., Wait while the checker verifies",
        "UNPROVEN, No failures were blamed on the PR, The notes below say what is missing"
    })
    void helpExplainsTheCurrentVerdict(String standing, String meaning, String action) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('renderVerdictHead')(verdict({ standing: '%s', blockers: %s }));
            report({ help: page.el('verdictHelp').title });
            """.formatted(standing, standing.equals("BLOCKERS") ? "[blocker('Test.test', 'occ-1')]" : "[]"));

        assertThat(out.get("help").asText()).contains(meaning, action).doesNotContain("glossary", "GitHub");
    }

    /** Given a merged PR, its retained verdict explains the tested code without advising a new run. */
    @ParameterizedTest
    @CsvSource({"WATCH", "UNPROVEN", "BLOCKERS"})
    void mergedHelpDescribesTheRetainedVerdict(String standing) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('renderVerdictHead')(verdict({ standing: '%s', mergedAt: 1700000000, blockers: %s }));
            report({ help: page.el('verdictHelp').title });
            """.formatted(standing, standing.equals("BLOCKERS") ? "[blocker('Test.test', 'occ-1')]" : "[]"));

        assertThat(out.get("help").asText()).contains("This PR is merged", "retained verdict", "a new run does not change it")
            .doesNotContain("Re-run", "Use Run", "latest code");
    }

    /** Given a client project, help names its available Run action and keeps the two-commit condition. */
    @ParameterizedTest
    @CsvSource({"python, false, new run of the suite", "extensions, true, new build chain"})
    void clientHelpNamesTheWholeRun(String repo, boolean composite, String action) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            page.run('renderVerdictHead')(verdict({ standing: 'WATCH', repo: '%s', composite: %s, blockers: [] }));
            report({ help: page.el('verdictHelp').title });
            """.formatted(repo, composite));

        assertThat(out.get("help").asText()).contains("Use Run above", action,
            "this PR's head and Ignite master are still at the same commits").doesNotContain("Re-run the affected suites");
    }

    /** Given confirmed client blockers, help asks for a fix instead of treating them as unconfirmed failures. */
    @ParameterizedTest
    @CsvSource({"python, false", "extensions, true"})
    void confirmedClientBlockersNeedAFix(String repo, boolean composite) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const confirmed = Object.assign(blocker('Test.test', 'occ-1'), { doubts: [], codeRuns: 3 });
            page.run('renderVerdictHead')(verdict({ repo: '%s', composite: %s, blockers: [confirmed] }));
            report({ help: page.el('verdictHelp').title });
            """.formatted(repo, composite));

        assertThat(out.get("help").asText()).contains("fix confirmed blockers").doesNotContain("Use Run", "whether these tests are blockers");
    }

    /** Given a client blocker seen only once, help keeps the project-wide run action and its commit condition. */
    @ParameterizedTest
    @CsvSource({"python, false", "extensions, true"})
    void oneRunClientBlockersCanBeCheckedByAWholeRun(String repo, boolean composite) throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            await page.load('?pr=13575');
            const one = Object.assign(blocker('Test.test', 'occ-1'), { doubts: ['ONE_RUN'], codeRuns: 1 });
            page.run('renderVerdictHead')(verdict({ repo: '%s', composite: %s, blockers: [one] }));
            report({ help: page.el('verdictHelp').title });
            """.formatted(repo, composite));

        assertThat(out.get("help").asText()).contains("Use Run above", "the same commits").doesNotContain("Re-run its suite");
    }

    /** Given the verdict header, its help is a keyboard-operable button that cannot navigate away from the result. */
    @Test
    void helpStaysOnThePage() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        int start = html.indexOf("<button id=\"verdictHelp\"");
        assertThat(start).isNotNegative();
        String button = html.substring(start, html.indexOf("</button>", start));

        assertThat(button).contains("type=\"button\"", "aria-label=\"What this means\"").doesNotContain("href=");
    }
}
