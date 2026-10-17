package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.Test;

/**
 * The PR's author names a parameterized test and can print a build problem for TeamCity to report. A name with two
 * backticks in a row ended its code span in the PR comment early, and the rest of it became a link, an @-mention and
 * an HTML heading in a comment the checker's account posts in apache/ignite. TeamCity's problem texts went into the
 * comment and the visa as they were, and its "in build #1178" linked PR 1178 of apache/ignite.
 */
class TeamCityTextTest {
    private static final int PR = 13686;

    private static final String TRAP = "[x](http://e) @user <h1>No blockers</h1> *all* in build #1178";

    /** Shows nothing; GitHub and JIRA make no link of what it follows. */
    private static final String WJ = "\u2060";

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    @Test
    void aNameWithTwoBackticksStaysOneCodeSpan() {
        String md = visas.composeMarkdown(PR, result(List.of(verdict("org.apache.ignite.EvilTest.test[a``b " + TRAP
            + "]")), List.of()), null);

        String html = html(md);

        assertThat(html.split("<code>", -1)).hasSize(2);
        assertThat(html).contains("<code>EvilTest.test[a``b [x](http://e) @user &lt;h1&gt;No blockers&lt;/h1&gt; *all* "
            + "in build #1178]</code>").doesNotContain("href=\"http://e", "<h1>", "<em>");
    }

    @Test
    void aProblemFromTeamCityIsTextInThePrComment() {
        String md = visas.composeMarkdown(PR, result(List.of(), List.of(broken("Cache 1", TRAP))), null);

        String html = html(md);

        assertThat(html).doesNotContain("href=\"http://e", "<h1>", "<em>")
            .contains("Cache 1: [x](http:" + WJ + "//e) @" + WJ + "user &lt;h1&gt;No blockers&lt;/h1&gt; *all* in "
                + "build #" + WJ + "1178")
            .contains("1 suite has no reliable result ([x](http:" + WJ + "//e) @" + WJ + "user");
        assertThat(md).doesNotContain("@user", "#1178");
    }

    /** GitHub also makes links of bare web and e-mail addresses, which CommonMark leaves as text. */
    @Test
    void aBareAddressFromTeamCityIsNoLink() {
        String md = visas.composeMarkdown(PR, result(List.of(), List.of(broken("Cache 1", "see https://e.example, "
            + "www.e.example or a@e.example"))), null);
        String wiki = visas.compose(PR, result(List.of(), List.of(broken("Cache 1", "see https://e.example"))), null);

        assertThat(md).contains("- Cache 1: see https:" + WJ + "//e.example, www" + WJ + ".e.example or a@" + WJ
            + "e.example\n");
        assertThat(wiki).contains("- Cache 1: see https:" + WJ + "//e.example\n");
    }

    /** Several suites broken the same way start their line with TeamCity's text, where markdown reads a list. */
    @Test
    void aProblemStartingTheLineMakesNoList() {
        String md = visas.composeMarkdown(PR, result(List.of(), List.of(broken("Cache 1", "1. Fixed - see #7"),
            broken("Cache 2", "1. Fixed - see #7"))), null);

        String html = html(md);

        assertThat(html).contains("<li>1. Fixed - see #" + WJ + "7 (2 suites): Cache 1, Cache 2</li>")
            .doesNotContain("<ol>");
    }

    @Test
    void aLineBreakInTeamCitysTextStartsNoBlock() {
        String md = visas.composeMarkdown(PR, result(List.of(), List.of(broken("Cache 1", "timeout\n# Fixed\n\n"
            + "<h1>Clean</h1>"))), null);
        String wiki = visas.compose(PR, result(List.of(), List.of(broken("Cache 1", "timeout\nh1. Fixed"))), null);

        assertThat(html(md)).doesNotContain("<h1>").contains("Cache 1: timeout # Fixed  &lt;h1&gt;Clean&lt;/h1&gt;");
        assertThat(wiki).contains("- Cache 1: timeout h1. Fixed").doesNotContain("\nh1. Fixed");
    }

    @Test
    void aProblemFromTeamCityIsTextInTheVisa() {
        String trap = "[x|http://e] [~admin] !http://e/x.png! {color:red}red{color}";

        String wiki = visas.compose(PR, result(List.of(), List.of(broken("Cache 1", trap))), null);

        String escaped = "\\[x|http:" + WJ + "//e\\] \\[~admin\\] \\!http:" + WJ + "//e/x.png\\! "
            + "\\{color:red\\}red\\{color\\}";
        assertThat(wiki).contains("- Cache 1: " + escaped).contains("1 suite has no reliable result (" + escaped)
            .doesNotContain(trap);
    }

    @Test
    void aTestNameMakesNoImageInTheVisa() {
        String wiki = visas.compose(PR, result(List.of(verdict("org.apache.ignite.EvilTest.test[!http://e/x.png!]")),
            List.of()), null);

        assertThat(wiki).contains("{{EvilTest.test\\[\\!http:" + WJ + "//e/x.png\\!\\]}}");
    }

    private static String html(String md) {
        return HtmlRenderer.builder().build().render(Parser.builder().build().parse(md));
    }

    private static TestVerdict verdict(String name) {
        return new TestVerdict(1, name, "IgniteTests24Java8_Sql", 9500003L, "SQL", null, true, false,
            "not seen failing in 120 master runs; failed the last 3 of 3 runs on this branch", "FFF", 3);
    }

    private static BrokenSuite broken(String name, String problem) {
        return new BrokenSuite("IgniteTests24Java8_" + name.replace(" ", ""), 9500010L, name, List.of(problem), 0, 0);
    }

    private static AnalysisResult result(List<TestVerdict> blockers, List<BrokenSuite> broken) {
        return new AnalysisResult(PR, 9500000L, "pull/13686/head", System.currentTimeMillis(), blockers, List.of(),
            List.of(), broken, List.of(), 120, 9, false, 0, false, 0, 0, 0, 0, 0);
    }
}
