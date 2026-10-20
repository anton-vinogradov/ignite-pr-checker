package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

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
        assertThat(kept.blockers()).extracting(TestVerdict::name)
            .containsExactly("GridCommandHandlerTest.testCacheIdle");
        assertThat(kept.filtered()).extracting(TestVerdict::reason)
            .containsExactly("pre-existing: fails 15/98 on master on JDK 17");
        assertThat(restarted.atMerge(PR)).as("the page's head names the ticket from it, and when and at which commit "
            + "the PR was merged").contains(new GithubClient.PullState("IGNITE-28890 Fix it", false, true, MERGED_AT,
                "5be1c0d"));
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

    /** A PR merged long ago and opened now was just recomputed against a master that holds its own failures. */
    @Test
    void aVerdictComputedLongAfterTheMergeIsNotKept(@TempDir Path dir) {
        cache.putResult(9391879L, verdict(9391879L, (MERGED_AT + 86_400) * 1000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());
        MergedVerdicts merged = merged(dir);

        merged.sweep();
        merged.sweep();

        assertThat(merged.verdict(PR)).isEmpty();
        assertThat(dir.resolve(PR + ".json")).doesNotExist();
        verify(github, times(1)).prOutcome(PR);
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

    /** A verdict the disk could not take stays in memory: the page shows it until a restart. */
    @Test
    void aVerdictThatCannotBeWrittenIsKeptInMemory(@TempDir Path dir) throws Exception {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());
        Files.createDirectory(dir.resolve(PR + ".json.tmp"));
        MergedVerdicts merged = merged(dir);

        merged.sweep();

        assertThat(merged.verdict(PR).orElseThrow().mergedAt()).isEqualTo(MERGED_AT);
    }

    /** A kept verdict cut short on disk reads as none, and the page computes the verdict as for any PR. */
    @Test
    void aKeptVerdictThatCannotBeReadIsNone(@TempDir Path dir) throws Exception {
        cache.putResult(9391879L, verdict(9391879L, 1_000));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());
        merged(dir).sweep();
        Path kept = dir.resolve(PR + ".json");
        Files.writeString(kept, Files.readString(kept).substring(0, 40));

        assertThat(merged(dir).verdict(PR)).isEmpty();
    }

    /**
     * A deploy that changed the words of the reasons dropped the snapshot's results. A PR merged 15 minutes before it
     * had no other verdict from before its merge, so none was kept, and its failures later read as pre-existing.
     */
    @Test
    void aPrMergedJustBeforeADeployThatChangedTheWordsKeepsItsVerdict(@TempDir Path dir) throws Exception {
        AnalysisCache restarted = startedOn(snapshotInEarlierWords(dir, TestVerdict.RULES));
        openAre(13600);
        when(github.prOutcome(PR)).thenReturn(merged());

        assertThat(restarted.peekResult(9391879L)).as("no page gets the earlier words").isEmpty();
        assertThat(restarted.freshResults()).isEmpty();
        MergedVerdicts merged = new MergedVerdicts(github, restarted, dir.resolve("merged"), mapper);
        merged.load();
        merged.sweep();

        AnalysisResult kept = merged.verdict(PR).orElseThrow();
        assertThat(kept.buildId()).isEqualTo(9391879L);
        assertThat(kept.blockers()).extracting(TestVerdict::name)
            .containsExactly("GridCommandHandlerTest.testCacheIdle");
    }

    /** Verdicts of earlier rules are not what the PR had under these: none is kept. */
    @Test
    void verdictsOfEarlierRulesAreNotCarriedOver(@TempDir Path dir) throws Exception {
        AnalysisCache restarted = startedOn(snapshotInEarlierWords(dir, TestVerdict.RULES - 1));
        openAre(13600);

        new MergedVerdicts(github, restarted, (Path)null, mapper).sweep();

        assertThat(restarted.carriedOver()).isEmpty();
        verify(github, never()).prOutcome(anyInt());
    }

    /** A second restart before the sweep got to the PR keeps what the first carried over, until it expires. */
    @Test
    void resultsCarriedOverOutliveARestartButNotTheirExpiry(@TempDir Path dir) throws Exception {
        AnalysisCache first = startedOn(snapshotInEarlierWords(dir, TestVerdict.RULES));
        Path again = dir.resolve("again.json");
        first.saveTo(again);

        assertThat(startedOn(again).carriedOver()).extracting(AnalysisResult::buildId).containsExactly(9391879L);

        ObjectNode expired = (ObjectNode)mapper.readTree(again.toFile());
        ((ObjectNode)expired.withArray("carriedOver").get(0)).put("expiresAt", System.currentTimeMillis() - 1);
        mapper.writeValue(again.toFile(), expired);

        assertThat(startedOn(again).carriedOver()).isEmpty();
    }

    /**
     * The snapshot of the release before a change of words, as v1.35.0 wrote it: the result of a PR merged at
     * {@link #MERGED_AT}, computed 15 minutes before, under {@code rules}.
     */
    private Path snapshotInEarlierWords(Path dir, int rules) throws Exception {
        AnalysisCache before = new AnalysisCache(cfg, mapper);
        before.putResult(9391879L, verdict(9391879L, (MERGED_AT - 900) * 1000));
        Path file = dir.resolve("analysis.json");
        before.saveTo(file);
        ObjectNode old = (ObjectNode)mapper.readTree(file.toFile());
        old.remove("carriedOver");
        old.put("rules", rules);
        old.put("texts", AnalysisCache.TEXTS - 1);
        mapper.writeValue(file.toFile(), old);

        return file;
    }

    private AnalysisCache startedOn(Path snapshot) throws Exception {
        AnalysisCache started = new AnalysisCache(cfg, mapper);
        started.loadFrom(snapshot);

        return started;
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
        TestVerdict blocker = new TestVerdict(42L, "GridCommandHandlerTest.testCacheIdle", "Suite", buildId + 1,
            "Suite", "occ", true, false, "not seen failing in 85 master runs; failed all 3 runs on this branch",
            "FFF", 3);
        TestVerdict filtered = new TestVerdict(43L, "TxRecoveryTest.testRecovery", "Suite", buildId + 1, "Suite", "occ",
            false, false, "pre-existing: fails 15/98 on master on JDK 17", "", 0);

        return new AnalysisResult(PR, buildId, "pull/" + PR + "/head", computedAt, List.of(blocker), List.of(),
            List.of(filtered), List.of(), List.of(), 140, 0, false, 0, false, 0, 0, 0, 1_791_000_000L, 0);
    }
}
