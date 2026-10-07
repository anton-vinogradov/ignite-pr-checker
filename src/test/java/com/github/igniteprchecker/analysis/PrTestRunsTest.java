package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.PrTests;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * TcpDiscoveryClientTopologyGapTest came in with PR 13327 taking 298 s, passed there once, and now fails 18 of 100
 * master runs, noise in 77 PRs: nothing in the verdict showed the PR's own tests apart. The PR's new and changed
 * test classes are now looked up in its RunAll, with each test's status, duration and master history.
 */
class PrTestRunsTest {
    private static final String TOK = "tok";

    private static final int PR = 13327;

    private static final long CHAIN = 9391879L;

    private static final String GAP = "modules/core/src/test/java/org/apache/ignite/spi/discovery/tcp/"
        + "TcpDiscoveryClientTopologyGapTest.java";

    private static final String HANDLER = "modules/control-utility/src/test/java/org/apache/ignite/util/"
        + "GridCommandHandlerTest.java";

    private static final String CONTROL = "IgniteTests24Java8_ControlUtility";

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final PrTestRuns prTests = new PrTestRuns(github, tc, new AnalysisCache(cfg, new ObjectMapper()));

    @Test
    void eachNewOrChangedTestClassShowsHowItsTestsRan() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(GAP, "added"),
            new GithubClient.PrFile(HANDLER, "modified")));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(List.of(
            run(1, "IgniteSpiDiscoverySelfTestSuite: org.apache.ignite.spi.discovery.tcp."
                    + "TcpDiscoveryClientTopologyGapTest.testClientReconnect", "SUCCESS", 298_000,
                "IgniteTests24Java8_Spi"),
            run(2, "IgniteControlUtilityTestSuite: org.apache.ignite.util.GridCommandHandlerTest.testCacheIdle",
                "SUCCESS", 1_200, CONTROL),
            run(3, "IgniteControlUtilityTestSuite: org.apache.ignite.util.GridCommandHandlerTest.testNewOption",
                "FAILURE", 3_000, CONTROL),
            run(4, "IgniteControlUtilityTestSuite: org.apache.ignite.util.GridCommandHandlerTestBase.testBase",
                "SUCCESS", 100, CONTROL))));
        when(tc.getBaseBranchHistory(TOK, 2, CONTROL)).thenReturn(masterRuns(85));
        when(tc.getBaseBranchHistory(TOK, 3, CONTROL)).thenReturn(List.of());

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.note()).isNull();
        assertThat(answer.classes()).extracting(PrTests.TestClass::name, PrTests.TestClass::added)
            .containsExactly(tuple("org.apache.ignite.spi.discovery.tcp.TcpDiscoveryClientTopologyGapTest", true),
                tuple("org.apache.ignite.util.GridCommandHandlerTest", false));
        assertThat(answer.classes().get(0).runs())
            .extracting(PrTests.Run::status, PrTests.Run::durationMs, PrTests.Run::suiteName, PrTests.Run::masterRuns)
            .containsExactly(tuple("SUCCESS", 298_000L, "Spi", null));
        assertThat(answer.classes().get(1).runs()).extracting(PrTests.Run::testId, PrTests.Run::masterRuns)
            .containsExactly(tuple(2L, 85), tuple(3L, 0));
        verify(tc).testRunsOfClasses(TOK, CHAIN,
            List.of("TcpDiscoveryClientTopologyGapTest", "GridCommandHandlerTest"));
        verify(tc, never()).getBaseBranchHistory(TOK, 1, "IgniteTests24Java8_Spi");
    }

    @Test
    void aPrsFilesAreAskedForOnce() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(GAP, "added")));
        when(tc.testRunsOfClasses(eq(TOK), anyLong(), any())).thenReturn(Optional.of(List.of()));

        prTests.of(TOK, PR, CHAIN, false);
        prTests.of(TOK, PR, CHAIN, false);
        prTests.of(TOK, PR, CHAIN + 1, false);

        verify(github, times(1)).prTestFiles(PR);
        verify(tc, times(2)).testRunsOfClasses(eq(TOK), anyLong(), any());
    }

    @Test
    void aPrThatChangesNoTestAsksTeamCityNothing() {
        when(github.prTestFiles(PR)).thenReturn(List.of());

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.classes()).isEmpty();
        assertThat(answer.note()).isNull();
        verifyNoInteractions(tc);
    }

    @Test
    void aClassNoSuiteRanHasNoRuns() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(GAP, "added")));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(List.of()));

        assertThat(prTests.of(TOK, PR, CHAIN, false).classes()).singleElement()
            .satisfies(c -> assertThat(c.runs()).isEmpty());
    }

    /**
     * PR 13327's first RunAll was the chain analysed while it ran, and the SPI suite with its new class had not
     * finished when the page asked. Once the chain finished, the class failed in 298 s, and the page asked again: it
     * got the answer from before, kept for 15 minutes.
     */
    @Test
    void anAnswerAboutAChainStillGoingIsNotTheOneOnceItFinished() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(GAP, "added")));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(List.of()))
            .thenReturn(Optional.of(List.of(run(1, "IgniteSpiDiscoverySelfTestSuite: org.apache.ignite.spi.discovery."
                + "tcp.TcpDiscoveryClientTopologyGapTest.testClientReconnect", "FAILURE", 298_000,
                "IgniteTests24Java8_Spi"))));

        PrTests going = prTests.of(TOK, PR, CHAIN, true);
        PrTests goingAgain = prTests.of(TOK, PR, CHAIN, true);
        PrTests finished = prTests.of(TOK, PR, CHAIN, false);

        assertThat(going.note()).isEqualTo("the RunAll is still going");
        assertThat(going.classes()).singleElement().satisfies(c -> assertThat(c.runs()).isEmpty());
        assertThat(goingAgain).as("kept for a little while").isSameAs(going);
        assertThat(finished.note()).isNull();
        assertThat(finished.classes()).singleElement().satisfies(c -> assertThat(c.runs())
            .extracting(PrTests.Run::status, PrTests.Run::durationMs).containsExactly(tuple("FAILURE", 298_000L)));
        verify(tc, times(2)).testRunsOfClasses(eq(TOK), eq(CHAIN), any());
    }

    @Test
    void masterHistoryIsLookedUpForTwentyTestsAtMostFailedOnesFirst() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(HANDLER, "modified")));
        List<TcModel.TestOccurrence> runs = new ArrayList<>(IntStream.range(0, 30).mapToObj(i -> run(100 + i,
            "IgniteControlUtilityTestSuite: org.apache.ignite.util.GridCommandHandlerTest.test" + i, "SUCCESS", i,
            CONTROL)).toList());
        runs.add(run(7, "IgniteControlUtilityTestSuite: org.apache.ignite.util.GridCommandHandlerTest.testBroken",
            "FAILURE", 1, CONTROL));
        Collections.reverse(runs);
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.of(runs));
        when(tc.getBaseBranchHistory(eq(TOK), anyLong(), eq(CONTROL))).thenReturn(masterRuns(3));

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.note()).isEqualTo("master history was looked up for 20 of 31 tests of changed classes");
        assertThat(answer.classes().get(0).runs().stream().filter(r -> r.masterRuns() != null)).hasSize(20);
        verify(tc, times(20)).getBaseBranchHistory(eq(TOK), anyLong(), eq(CONTROL));
        verify(tc).getBaseBranchHistory(TOK, 7, CONTROL);
    }

    @Test
    void aGithubErrorIsSaidAndAskedAgainNextTime() {
        when(github.prTestFiles(PR)).thenThrow(new IllegalStateException("GitHub: 502")).thenReturn(List.of());

        assertThat(prTests.of(TOK, PR, CHAIN, false).note()).isEqualTo("GitHub could not list the PR's files");
        assertThat(prTests.of(TOK, PR, CHAIN, false).note()).isNull();
    }

    @Test
    void aTeamCityThatRejectsTheQuerySaysSo() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(GAP, "added")));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenReturn(Optional.empty());

        PrTests answer = prTests.of(TOK, PR, CHAIN, false);

        assertThat(answer.note()).isEqualTo("TeamCity does not answer how they ran");
        assertThat(answer.classes()).singleElement().satisfies(c -> assertThat(c.runs()).isEmpty());
    }

    @Test
    void aTeamCityErrorIsSaidAndAskedAgainNextTime() {
        when(github.prTestFiles(PR)).thenReturn(List.of(new GithubClient.PrFile(GAP, "added")));
        when(tc.testRunsOfClasses(eq(TOK), eq(CHAIN), any())).thenThrow(new IllegalStateException("TeamCity: 502"))
            .thenReturn(Optional.of(List.of()));

        assertThat(prTests.of(TOK, PR, CHAIN, false).note()).isEqualTo("TeamCity could not be asked how they ran");
        assertThat(prTests.of(TOK, PR, CHAIN, false).note()).isNull();
    }

    private static TcModel.TestOccurrence run(long testId, String name, String status, long durationMs, String suite) {
        return new TcModel.TestOccurrence("build:(id:9391900),id:" + testId, name, status, new TcModel.TestRef(testId),
            new TcModel.BuildRef(9391900L, null, null, null, suite, new TcModel.BuildType(suite,
                suite.substring(suite.indexOf('_') + 1)), null, null), null, durationMs);
    }

    private static List<TcModel.TestOccurrence> masterRuns(int passes) {
        return IntStream.range(0, passes).mapToObj(i -> new TcModel.TestOccurrence(null, null, "SUCCESS", null, null,
            null)).toList();
    }
}
