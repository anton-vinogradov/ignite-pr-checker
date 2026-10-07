package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The verdict of 13583 said "489 tests started failing" and listed ten lines of 220 characters, suite and full
 * package each, all of one class, then "… and 479 more": no telling it was one class, no reason and no way into
 * TeamCity. A class-level failure showed an empty method. The tests are now grouped by run and class.
 */
class ReadableTestListTest {
    private static final int PR = 13583;

    private static final String QUERY_TEST = "org.apache.ignite.internal.processors.query.IgniteCacheReplicatedQuerySelfTest";

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    @Test
    void manyFailuresOfOneClassReadAsOneLine() {
        List<TestVerdict> blockers = new ArrayList<>();
        IntStream.range(0, 489).forEach(i -> blockers.add(verdict(i, "IgniteQueryTestSuite: " + QUERY_TEST + ".test" + i,
            "IgniteTests24Java8_Queries1", 9500001L, "Queries 1")));

        String md = visas.composeMarkdown(PR, result(blockers), null);
        String wiki = visas.compose(PR, result(blockers), null);

        assertThat(md).contains("❌ **489 blocker(s) in 1 suite(s):**\n- Queries 1 · `IgniteCacheReplicatedQuerySelfTest`"
                + " — 489 tests: `test0`, `test1`, `test2` and 486 more · [TC](https://ci2.example/buildConfiguration/"
                + "IgniteTests24Java8_Queries1/9500001?buildTab=tests)")
            .doesNotContain("org.apache.ignite", "IgniteQueryTestSuite", "479 more");
        assertThat(wiki).contains("- Queries 1 · {{IgniteCacheReplicatedQuerySelfTest}} — 489 tests: {{test0}},"
            + " {{test1}}, {{test2}} and 486 more · [TC|https://ci2.example/buildConfiguration/IgniteTests24Java8_Queries1/"
            + "9500001?buildTab=tests]");
    }

    @Test
    void aLoneTestSaysWhyAndLinksItsRowInTeamCity() {
        TestVerdict t = new TestVerdict(-5272433775095107011L, "IgniteClientTestSuite: org.apache.ignite.client"
            + ".ClientReconnectTest.testReconnect", "IgniteTests24Java8_Client", 9500002L, "Client", "build:(id:9500002),id:7",
            true, false, "no master history (can't prove pre-existing); failed the last 1 of 1 runs on this branch", "PPF",
            1, List.of(TestVerdict.Doubt.ONE_RUN, TestVerdict.Doubt.NO_MASTER_HISTORY));

        String md = visas.composeMarkdown(PR, result(List.of(t)), null);

        assertThat(md).contains("- Client · `ClientReconnectTest.testReconnect` — 1 run; new test / no master history"
            + " · [TC](https://ci2.example/buildConfiguration/IgniteTests24Java8_Client/9500002?buildTab=tests"
            + "&expandedTest=build%3A%28id%3A9500002%29%2Cid%3A7#testNameId13174310298614444605)");
    }

    @Test
    void aFailureOfTheClassItselfIsNamedSo() {
        TestVerdict t = verdict(1, "org.apache.ignite.client.ClientReconnectTest.", "IgniteTests24Java8_Client",
            9500002L, "Client");

        assertThat(visas.composeMarkdown(PR, result(List.of(t)), null))
            .contains("- Client · `ClientReconnectTest` (class-level failure) — failed 3 of 3 runs of this code");
        assertThat(visas.compose(PR, result(List.of(t)), null))
            .contains("- Client · {{ClientReconnectTest}} (class-level failure) — failed 3 of 3 runs of this code");
    }

    @Test
    void pastFiveGroupsTheRestFoldInThePrComment() {
        List<TestVerdict> blockers = new ArrayList<>();
        IntStream.range(0, 7).forEach(i -> blockers.add(verdict(i, "org.apache.ignite.Test" + i + ".testIt",
            "IgniteTests24Java8_Cache" + i, 9600000L + i, "Cache " + i)));
        blockers.add(verdict(7, "org.apache.ignite.Test0.testOther", "IgniteTests24Java8_Cache0", 9600000L, "Cache 0"));

        String md = visas.composeMarkdown(PR, result(blockers), null);

        assertThat(md).startsWith("**[Ignite PR Checker]")
            .contains("- Cache 0 · `Test0` — 2 tests: `testIt`, `testOther`")
            .contains("- Cache 4 · `Test4.testIt`")
            .contains("<details><summary>2 more test(s) in 2 class(es)</summary>\n\n- Cache 5 · `Test5.testIt`")
            .endsWith("- Cache 6 · `Test6.testIt` — failed 3 of 3 runs of this code · [TC](https://ci2.example/"
                + "buildConfiguration/IgniteTests24Java8_Cache6/9600006?buildTab=tests#testNameId6)\n\n</details>\n");
        assertThat(md.indexOf("Cache 0")).isLessThan(md.indexOf("Cache 1"));
    }

    @Test
    void theBiggestGroupLeadsTheList() {
        List<TestVerdict> blockers = new ArrayList<>();
        IntStream.range(0, 6).forEach(i -> blockers.add(verdict(i, "org.apache.ignite.Test" + i + ".testIt",
            "IgniteTests24Java8_Cache" + i, 9600000L + i, "Cache " + i)));
        IntStream.range(0, 489).forEach(i -> blockers.add(verdict(100 + i, QUERY_TEST + ".test" + i,
            "IgniteTests24Java8_Queries1", 9500001L, "Queries 1")));

        String md = visas.composeMarkdown(PR, result(blockers), null);

        assertThat(md).contains(":**\n- Queries 1 · `IgniteCacheReplicatedQuerySelfTest` — 489 tests");
        assertThat(md.indexOf("Queries 1")).isLessThan(md.indexOf("<details>"));
    }

    @Test
    void pastFiftyGroupsTheRestAreOnThePage() {
        List<TestVerdict> blockers = new ArrayList<>();
        IntStream.range(0, 60).forEach(i -> blockers.add(verdict(i, "org.apache.ignite.Test" + i + ".testIt",
            "IgniteTests24Java8_Cache" + i, 9600000L + i, "Cache " + i)));

        String md = visas.composeMarkdown(PR, result(blockers), null);

        assertThat(md).contains("- Cache 49 · `Test49.testIt`").doesNotContain("Test50")
            .contains("- … and 10 more test(s) in 10 class(es) on [the checker's page]"
                + "(https://checker.example/?pr=13583)\n\n</details>\n");
    }

    /**
     * A wide breakage with parameterized names: 80 classes in each of the three lists made a comment of 70 633
     * characters, and GitHub refuses one over 65 536 — the verdict did not reach the PR at all.
     */
    @Test
    void aWideBreakageStaysWithinGithubsLimit() {
        List<List<TestVerdict>> lists = new ArrayList<>();
        for (int list = 0; list < 3; list++) {
            List<TestVerdict> tests = new ArrayList<>();
            for (int cls = 0; cls < 80; cls++)
                for (int m = 0; m < (cls < 53 ? 4 : 1); m++)
                    tests.add(verdict(list * 1000 + cls * 10 + m, "IgniteCacheTestSuite3: org.apache.ignite.internal"
                        + ".processors.cache.distributed.near.GridCacheNearMultiNodeSelfTest" + list + "x" + cls
                        + ".testSomethingLonger" + m + "[mode=PARTITIONED, atomicity=TRANSACTIONAL, backups=1, "
                        + "persistence=true]", "IgniteTests24Java8_Cache3", 9600000L + cls, "Cache 3"));
            lists.add(tests);
        }
        AnalysisResult r = new AnalysisResult(PR, 9500000L, "pull/13583/head", System.currentTimeMillis(),
            lists.get(0), lists.get(1), List.of(), List.of(), List.of(), 120, 9, false, 0, false, 0, 0, 0, 0, 0,
            List.of(), List.of(), lists.get(2), 0);

        String md = visas.composeMarkdown(PR, r, null);

        assertThat(md.length()).isLessThan(60_000);
        assertThat(md).contains("❌ **239 blocker(s) in 80 suite(s):**\n- Cache 3 · `GridCacheNearMultiNodeSelfTest0x0`"
            + " — 4 tests")
            .contains("more test(s) in", "on [the checker's page](https://checker.example/?pr=13583)");
    }

    @Test
    void aVisaShowsTenGroupsAndCountsTheRest() {
        List<TestVerdict> blockers = new ArrayList<>();
        IntStream.range(0, 12).forEach(i -> blockers.add(verdict(i, "org.apache.ignite.Test" + i + ".testIt",
            "IgniteTests24Java8_Cache" + i, 9600000L + i, "Cache " + i)));

        assertThat(visas.compose(PR, result(blockers), null)).contains("- Cache 9 · {{Test9.testIt}}")
            .doesNotContain("Test10")
            .contains("… and 2 more test(s) in 2 class(es) on [the checker's page|https://checker.example/?pr=13583]");
    }

    /** IGNITE-28877: "[sqlTxMode=NONE]" inside {{…}} was read as a link and shown as a red error. */
    @Test
    void aParameterizedNameIsTextInTheVisa() {
        TestVerdict t = verdict(1, "org.apache.ignite.sql.SqlTxTest.testInsert[sqlTxMode=NONE, by={x}\\y]",
            "IgniteTests24Java8_Sql", 9500003L, "SQL");

        assertThat(visas.compose(PR, result(List.of(t)), null))
            .contains("{{SqlTxTest.testInsert\\[sqlTxMode=NONE, by=\\{x\\}\\\\y\\]}}");
    }

    @Test
    void aNameCannotPutAMentionIntoTheVisa() {
        TestVerdict t = verdict(1, "org.apache.ignite.EvilTest.test[~admin]", "IgniteTests24Java8_Sql", 9500003L, "SQL");

        assertThat(visas.compose(PR, result(List.of(t)), null)).contains("{{EvilTest.test\\[~admin\\]}}")
            .doesNotContain("{{EvilTest.test[~admin]}}");
    }

    private static TestVerdict verdict(long id, String name, String suite, long suiteBuildId, String suiteName) {
        return new TestVerdict(id, name, suite, suiteBuildId, suiteName, null, true, false,
            "not seen failing in 120 master run(s); failed the last 3 of 3 runs on this branch", "FFF", 3);
    }

    private static AnalysisResult result(List<TestVerdict> blockers) {
        return new AnalysisResult(PR, 9500000L, "pull/13583/head", System.currentTimeMillis(), blockers, List.of(),
            List.of(), List.of(), List.of(), 120, 9, false, 0, false, 0, 0, 0, 0, 0);
    }
}
