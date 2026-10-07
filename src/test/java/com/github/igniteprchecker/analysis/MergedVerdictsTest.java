package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ci2 keeps about 15 days of builds, PR comments and visas name only how many tests were filtered, and a merged
 * PR's page was recomputed against a master history that by then held the PR's own failures: they read as
 * "pre-existing". So nothing could show later how many breaks the checker let through. A merged PR now keeps
 * the verdict it had at the merge, in a file of its own, and is served that verdict instead of a recompute.
 */
class MergedVerdictsTest {
    private static final int PR = 13566;

    /** 2026-10-05 14:02:00 UTC. */
    private static final long MERGED_AT = 1_791_208_920L;

    private final ObjectMapper mapper = new ObjectMapper();

    private final AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);

    private final AnalysisCache cache = new AnalysisCache(cfg, mapper);

    private final GithubClient github = mock(GithubClient.class);

    @Test
    void aMergedPrKeepsTheVerdictItHadAtTheMerge(@TempDir Path dir) throws Exception {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        cache.putResult(9391500L, verdict(9391500L, 500));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());

        merged(dir).sweep();

        assertThat(Files.readString(dir.resolve(PR + ".json")))
            .contains("\"mergeCommit\":\"c0ffee1\"", "\"rules\":" + TestVerdict.RULES,
                "\"reason\":\"pre-existing: fails 15/98 on master on JDK 17\"");
        MergedVerdicts restarted = merged(dir);
        AnalysisResult kept = restarted.verdict(PR).orElseThrow();
        assertThat(kept.buildId()).as("the one shown last").isEqualTo(9391879L);
        assertThat(kept.mergedAt()).isEqualTo(MERGED_AT);
        assertThat(kept.blockers()).extracting(TestVerdict::name).containsExactly("GridCommandHandlerTest.testCacheIdle");
        assertThat(kept.filtered()).extracting(TestVerdict::reason)
            .containsExactly("pre-existing: fails 15/98 on master on JDK 17");
    }

    @Test
    void aMergedPrIsServedItsKeptVerdictInsteadOfARecompute(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());
        MergedVerdicts merged = merged(dir);
        merged.sweep();
        TcClient tc = mock(TcClient.class);
        ChainCollector chains = mock(ChainCollector.class);
        BlockerAnalyzer analyzer = new BlockerAnalyzer(tc, chains, cfg, Executors.newFixedThreadPool(2),
            Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(2), cache,
            new RunDeltaStore(mapper), merged);

        assertThat(analyzer.analyze("t", PR).orElseThrow().mergedAt()).isEqualTo(MERGED_AT);
        assertThat(analyzer.forceRefresh("t", PR).orElseThrow().mergedAt()).isEqualTo(MERGED_AT);
        verifyNoInteractions(tc, chains);
    }

    @Test
    void aPrClosedWithoutAMergeIsLeftAlone(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(false, true, 0, null, "5be1c0d", "x"));
        MergedVerdicts merged = merged(dir);

        merged.sweep();
        merged.sweep();

        assertThat(merged.verdict(PR)).isEmpty();
        assertThat(dir.resolve(PR + ".json")).doesNotExist();
        verify(github, times(1)).prOutcome(PR);
    }

    /** The open list is GitHub's 50 most recently updated PRs: one past it is asked about again only hours later. */
    @Test
    void aPrOpenPastTheListIsNotAskedAboutEverySweep(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(new GithubClient.PrOutcome(false, false, 0, null, "5be1c0d", "x"));
        MergedVerdicts merged = merged(dir);

        merged.sweep();
        merged.sweep();

        assertThat(merged.verdict(PR)).isEmpty();
        verify(github, times(1)).prOutcome(PR);
    }

    @Test
    void anOpenPrIsNotAskedAbout(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(PR, 13600);

        merged(dir).sweep();

        verify(github, never()).prOutcome(anyInt());
    }

    /** With the list unknown, every analysed PR would look gone. */
    @Test
    void nothingIsAskedWhileGithubSendsNoList(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        when(github.openPrs()).thenReturn(List.of());

        merged(dir).sweep();

        verify(github, never()).prOutcome(anyInt());
    }

    /** The sweep runs on Spring's scheduler: GitHub failing one call is not asked the rest. */
    @Test
    void aGithubErrorEndsTheSweep(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        cache.putResult(9392879L, new AnalysisResult(13567, 9392879L, "pull/13567/head", 1_000, List.of(), List.of(),
            List.of(), List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, 1_791_000_000L, 0));
        openAre(13600);
        when(github.prOutcome(anyInt())).thenThrow(new IllegalStateException("GitHub timed out"));

        merged(dir).sweep();

        verify(github, times(1)).prOutcome(anyInt());
    }

    @Test
    void withoutADirectoryTheVerdictIsKeptInMemory() {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());
        MergedVerdicts merged = new MergedVerdicts(github, cache, (Path)null, mapper);

        merged.sweep();

        assertThat(merged.verdict(PR).orElseThrow().mergedAt()).isEqualTo(MERGED_AT);
    }

    private MergedVerdicts merged(Path dir) {
        MergedVerdicts merged = new MergedVerdicts(github, cache, dir, mapper);
        merged.load();

        return merged;
    }

    private void openAre(int... prs) {
        List<PrSummary> open = Arrays.stream(prs)
            .mapToObj(n -> new PrSummary(n, "IGNITE-1 x", "u", null, null, null)).toList();
        when(github.openPrs()).thenReturn(open);
    }

    private static GithubClient.PrOutcome merged() {
        return new GithubClient.PrOutcome(true, true, MERGED_AT, "c0ffee1", "5be1c0d", "IGNITE-28890 Fix it");
    }

    private static AnalysisResult verdict(long buildId, long computedAt) {
        TestVerdict blocker = new TestVerdict(42L, "GridCommandHandlerTest.testCacheIdle", "Suite", buildId + 1, "Suite",
            "occ", true, false, "not seen failing in 85 master run(s); failed all 3 runs on this branch", "FFF", 3);
        TestVerdict filtered = new TestVerdict(43L, "TxRecoveryTest.testRecovery", "Suite", buildId + 1, "Suite", "occ",
            false, false, "pre-existing: fails 15/98 on master on JDK 17", "", 0);

        return new AnalysisResult(PR, buildId, "pull/" + PR + "/head", computedAt, List.of(blocker), List.of(),
            List.of(filtered), List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, 1_791_000_000L, 0);
    }
}
