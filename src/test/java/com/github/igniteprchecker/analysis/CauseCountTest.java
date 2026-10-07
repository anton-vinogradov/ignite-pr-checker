package com.github.igniteprchecker.analysis;

import static com.github.igniteprchecker.analysis.CauseClustersTest.blocker;
import static com.github.igniteprchecker.analysis.CauseClustersTest.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * PR 13654's root causes after a re-run: the header said 7 causes over 24 blockers while 23 were left, since
 * the groups were kept per build. Seven blockers whose run of 23 September TeamCity had no message for any
 * more were one cause, "(no failure message)", with an "ai" asking what they share. And the first 80 blockers
 * in suite order were sampled, so one big suite hid the causes of all the others. Dating message-less runs
 * costs a TeamCity call each, so a few are dated.
 */
class CauseCountTest {
    private static final String TOK = "t";

    private static final String CACHE = "IgniteTests24Java8_Cache2";

    private static final String QUERIES = "IgniteTests24Java8_Queries1";

    private final TcClient tc = mock(TcClient.class);

    private final FailureDetails failures = new FailureDetails(tc);

    private final CauseClusters causes = new CauseClusters(tc, failures, Executors.newFixedThreadPool(2));

    @Test
    void reRunThatClearedABlockerRegroupsTheRest() {
        when(tc.testDetails(TOK, "o1")).thenReturn("Partition 1 lost");
        when(tc.testDetails(TOK, "o2")).thenReturn("Lock was not released");

        CauseClusters.Result before = causes.clusters(TOK, run(blocker(1, CACHE, "o1"), blocker(2, QUERIES, "o2")));
        CauseClusters.Result after = causes.clusters(TOK, run(blocker(1, CACHE, "o1")));

        assertThat(before.clusters()).hasSize(2);
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.clusters()).extracting(CauseClusters.Cluster::signature).containsExactly("Partition N lost");
    }

    @Test
    void blockersWithoutAReadableMessageAreNoCause() {
        when(tc.testDetails(TOK, "o1")).thenReturn("Partition 1 lost");
        when(tc.testDetails(TOK, "gone1")).thenReturn("");
        when(tc.testDetails(TOK, "gone2")).thenReturn(null);
        when(tc.testDetails(TOK, "blip")).thenThrow(new ResourceAccessException("ci2 timed out"));
        when(tc.getBuildState(TOK, 9100L)).thenReturn(build(9100L, "20260923T011638+0300"));

        CauseClusters.Result r = causes.clusters(TOK, run(blocker(1, CACHE, "o1"), inRun(2, "gone1", 9100L),
            inRun(3, "gone2", 9100L), blocker(4, QUERIES, "blip")));

        assertThat(r.clusters()).extracting(CauseClusters.Cluster::signature).containsExactly("Partition N lost");
        assertThat(r.noMessage()).extracting(CauseClusters.Member::testId).containsExactly(2L, 3L);
        assertThat(r.unread()).extracting(CauseClusters.Member::testId).containsExactly(4L);
        assertThat(r.runStartedAt()).isEqualTo(Map.of(9100L, 1790115398L));
    }

    @Test
    void datesAreAskedForFiveRunsAtMost() {
        when(tc.testDetails(anyString(), anyString())).thenReturn("");
        when(tc.getBuildState(eq(TOK), anyLong()))
            .thenAnswer(call -> build(call.getArgument(1), "20260923T011638+0300"));
        TestVerdict[] silent = LongStream.range(0, 7).mapToObj(i -> inRun(i, "gone" + i, 9100L + i))
            .toArray(TestVerdict[]::new);

        CauseClusters.Result r = causes.clusters(TOK, run(silent));

        assertThat(r.noMessage()).hasSize(7);
        assertThat(r.runStartedAt()).hasSize(5);
        verify(tc, times(5)).getBuildState(eq(TOK), anyLong());
    }

    @Test
    void messageTeamCityDidNotAnswerForIsAskedAgain() {
        when(tc.testDetails(TOK, "o1")).thenReturn("Partition 1 lost");
        when(tc.testDetails(TOK, "blip")).thenThrow(new ResourceAccessException("ci2 timed out"))
            .thenReturn("Lock was not released");
        var res = run(blocker(1, CACHE, "o1"), blocker(4, QUERIES, "blip"));

        CauseClusters.Result first = causes.clusters(TOK, res);
        CauseClusters.Result second = causes.clusters(TOK, res);

        assertThat(first.unread()).hasSize(1);
        assertThat(causes.cached(res)).isPresent();
        assertThat(second.unread()).isEmpty();
        assertThat(second.clusters()).hasSize(2);
        verify(tc, times(1)).testDetails(TOK, "o1");
    }

    @Test
    void sampleTakesEverySuiteInTurn() {
        when(tc.testDetails(anyString(), anyString())).thenReturn("Partition 1 lost");
        List<TestVerdict> blockers = new ArrayList<>();
        for (int i = 0; i < 100; i++)
            blockers.add(blocker(i, CACHE, "c" + i));
        blockers.add(blocker(1000, QUERIES, "q"));
        when(tc.testDetails(TOK, "q")).thenReturn("Lock was not released");

        CauseClusters.Result r = causes.clusters(TOK, run(blockers.toArray(TestVerdict[]::new)));

        assertThat(r.sampled()).isEqualTo(80);
        assertThat(r.clusters()).extracting(CauseClusters.Cluster::signature).contains("Lock was not released");
    }

    @Test
    void headerAsksOnlyForWhatIsAlreadyGrouped() {
        when(tc.testDetails(TOK, "o1")).thenReturn("Partition 1 lost");
        var res = run(blocker(1, CACHE, "o1"));

        assertThat(causes.cached(res)).isEmpty();
        causes.clusters(TOK, res);
        assertThat(causes.cached(res)).isPresent();
        assertThat(causes.cached(run(blocker(1, CACHE, "o1"), blocker(2, CACHE, "o2")))).isEmpty();
        verify(tc, times(1)).testDetails(TOK, "o1");
    }

    @Test
    void messageReadForTheGroupsIsNotFetchedAgainForWhyOrAi() {
        when(tc.testDetails(TOK, "o1")).thenReturn("Partition 1 lost");

        causes.clusters(TOK, run(blocker(1, CACHE, "o1")));
        String why = failures.of(TOK, "o1");
        String ai = failures.of("another user's token", "o1");

        assertThat(why).isEqualTo("Partition 1 lost").isEqualTo(ai);
        verify(tc, times(1)).testDetails(anyString(), anyString());
    }

    private static TestVerdict inRun(long testId, String occurrenceId, long suiteBuildId) {
        return new TestVerdict(testId, "Cache2: T.test" + testId, CACHE, suiteBuildId, "Cache 2", occurrenceId, true,
            false, "blocker", "F", 1);
    }

    private static TcModel.Build build(long id, String startDate) {
        return new TcModel.Build(id, "FAILURE", "finished", null, CACHE, null, null, startDate, null, null, null, null,
            null, null, null, null, null, null);
    }
}
