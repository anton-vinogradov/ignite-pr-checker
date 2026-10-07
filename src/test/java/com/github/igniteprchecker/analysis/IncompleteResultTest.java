package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.FailedTest;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * One 502 from ci2 while checking one failed test made it a blocker "could not verify (TeamCity error)".
 * Nothing was logged, the result was cached like any other, and the sweep posted a visa and re-ran the
 * suite by it. An incomplete result is marked, keeps the unchecked tests apart from the verdict, and is
 * retried.
 */
class IncompleteResultTest {
    private static final String TOK = "t";

    private static final int PR = 13654;

    private static final long CHAIN = 9391879L;

    private static final String CACHE1 = "IgniteTests24Java8_Cache1";

    private static final FailedTest UNLUCKY = new FailedTest(1L, "GridCacheTest.testPut", CACHE1, 9391901L, "Cache 1",
        "o1");

    private static final FailedTest BROKE = new FailedTest(2L, "GridCacheTest.testGet", CACHE1, 9391901L, "Cache 1",
        "o2");

    private final TcClient tc = mock(TcClient.class);

    private final ChainCollector chains = mock(ChainCollector.class);

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final ObjectMapper mapper = new ObjectMapper();

    private final AnalysisCache cache = new AnalysisCache(cfg, mapper);

    private final RunDeltaStore deltas = new RunDeltaStore(mapper);

    private final BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2),
        Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor(), cache, deltas);

    IncompleteResultTest() {
        when(chains.findBuildId(TOK, PR)).thenReturn(Optional.of(CHAIN));
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(chain(List.of()));
        for (FailedTest t : List.of(UNLUCKY, BROKE)) {
            when(tc.prBranchRuns(TOK, PR, t.testId(), CACHE1)).thenReturn(List.of(new TcModel.TestOccurrence(
                t.occurrenceId(), t.name(), "FAILURE", null, new TcModel.BuildRef(9391901L, null, "finished", "FAILURE",
                    CACHE1, null, null, null), null)));
        }
        when(tc.getBaseBranchHistory(TOK, BROKE.testId(), CACHE1)).thenReturn(cleanMaster());
    }

    @Test
    void aTestTeamCityFailedToAnswerForIsUncheckedNotABlocker() {
        when(tc.getBaseBranchHistory(TOK, UNLUCKY.testId(), CACHE1)).thenThrow(badGateway());

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.blockers()).extracting(TestVerdict::name).containsExactly(BROKE.name());
        assertThat(r.unverified()).extracting(TestVerdict::name, TestVerdict::reason, TestVerdict::blocker)
            .containsExactly(tuple(UNLUCKY.name(), "could not verify (TeamCity error: 502 Bad Gateway)", false));
        assertThat(r.filtered()).isEmpty();
        assertThat(r.incompleteSince()).isPositive();
        assertThat(Caveats.of(r, null)).contains("1 failed test(s) could not be checked (TeamCity errors)");
        assertThat(analyzer.stillRetrying(r)).as("held back from the visa and the re-run waves").isTrue();
        assertThat(deltas.history(PR)).as("an unchecked test is not a fixed blocker").isEmpty();
    }

    @Test
    void theFirstFailureIsLoggedOncePerCompute() {
        when(tc.getBaseBranchHistory(TOK, UNLUCKY.testId(), CACHE1)).thenThrow(badGateway());
        Logger logger = (Logger) LoggerFactory.getLogger(BlockerAnalyzer.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            analyzer.analyze(TOK, PR);
        }
        finally {
            logger.detachAppender(events);
        }

        assertThat(events.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
            .extracting(ILoggingEvent::getFormattedMessage).asString()
            .contains("PR 13654", "1 failed test(s) left unchecked", "first: GridCacheTest.testPut: 502 Bad Gateway");
    }

    /** The suite-level lookups fail safe, keeping the entry; the result is incomplete all the same. */
    @Test
    void aSuiteWhoseNewerRunsCouldNotBeReadMakesTheResultIncomplete() {
        when(chains.collectForBuild(eq(TOK), eq(PR), eq(CHAIN), any())).thenReturn(chain(List.of(
            new BrokenSuite(CACHE1, 9391901L, "Cache 1", List.of("execution timeout"), 10, 300))));
        when(tc.latestSuiteRun(TOK, PR, CACHE1)).thenThrow(badGateway());

        AnalysisResult r = analyzer.analyze(TOK, PR).orElseThrow();

        assertThat(r.brokenSuites()).extracting(BrokenSuite::suite).containsExactly(CACHE1);
        assertThat(r.unverified()).isEmpty();
        assertThat(r.incompleteSince()).isPositive();
    }

    @Test
    void aRetryThatFailsAgainKeepsWhenTheResultFirstWentIncomplete() {
        long since = System.currentTimeMillis() - 30 * 60_000;
        cache.putResult(CHAIN, incomplete(since, System.currentTimeMillis() - 25 * 60_000));
        when(tc.getBaseBranchHistory(TOK, UNLUCKY.testId(), CACHE1)).thenThrow(badGateway());

        AnalysisResult r = analyzer.analyzeForAction(TOK, PR).orElseThrow();

        assertThat(r.incompleteSince()).isEqualTo(since);
        assertThat(analyzer.stillRetrying(r)).as("held for 20 minutes, then acted on as it is").isFalse();
    }

    @Test
    void aRetryThatSucceedsMakesTheResultComplete() {
        cache.putResult(CHAIN, incomplete(System.currentTimeMillis() - 5 * 60_000, System.currentTimeMillis() - 5 * 60_000));
        when(tc.getBaseBranchHistory(TOK, UNLUCKY.testId(), CACHE1)).thenReturn(cleanMaster());

        AnalysisResult r = analyzer.analyzeForAction(TOK, PR).orElseThrow();

        assertThat(r.incompleteSince()).isZero();
        assertThat(r.unverified()).isEmpty();
        assertThat(r.blockers()).extracting(TestVerdict::name).containsExactlyInAnyOrder(UNLUCKY.name(), BROKE.name());
        assertThat(deltas.history(PR)).hasSize(1);
    }

    /** Ten minutes incomplete, computed three minutes ago: the next try waits ten minutes from then. */
    @Test
    void theWaitForARetryGrowsWithHowLongTheResultHasBeenIncomplete() {
        long now = System.currentTimeMillis();
        cache.putResult(CHAIN, incomplete(now - 13 * 60_000, now - 3 * 60_000));

        AnalysisResult r = analyzer.analyzeForAction(TOK, PR).orElseThrow();

        assertThat(r.computedAt()).isEqualTo(now - 3 * 60_000);
        verify(chains, never()).collectForBuild(eq(TOK), eq(PR), anyLong(), any());
    }

    private AnalysisResult incomplete(long since, long computedAt) {
        TestVerdict unchecked = new TestVerdict(UNLUCKY.testId(), UNLUCKY.name(), CACHE1, 9391901L, "Cache 1", "o1",
            false, false, "could not verify (TeamCity error: 502 Bad Gateway)", "", 0);

        return new AnalysisResult(PR, CHAIN, "pull/13654/head", computedAt, List.of(), List.of(), List.of(), List.of(),
            List.of(), 140, 0, false, 0, false, 0, 0, 0, computedAt / 1000 - 3600, computedAt / 1000 - 60, List.of(),
            List.of(), List.of(unchecked), since);
    }

    private static ChainCollector.Chain chain(List<BrokenSuite> broken) {
        long now = System.currentTimeMillis() / 1000;

        return new ChainCollector.Chain(CHAIN, "pull/13654/head", List.of(UNLUCKY, BROKE), broken, List.of(), 140, 0,
            false, 0, false, 0, now - 4 * 3600, now - 4 * 3600, now - 3600);
    }

    private static List<TcModel.TestOccurrence> cleanMaster() {
        return Collections.nCopies(100, new TcModel.TestOccurrence(null, null, "SUCCESS", null, null, null));
    }

    private static HttpServerErrorException badGateway() {
        return HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0],
            null);
    }
}
