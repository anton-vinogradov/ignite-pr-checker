package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisCachePersistenceTest {
    private static final RunEnv JDK17 = new RunEnv("17", "1.0");

    /** A test's master runs, newest first: green on JDK 17, failing on the nightly JDK 21 run. */
    private static final RunHistory MASTER_JDK21_BREAK = new RunHistory("PFPP", "abaa",
        List.of(JDK17, new RunEnv("21", "1.0")), List.of());

    private final ObjectMapper mapper = new ObjectMapper();

    private static AnalysisProperties props() {
        return new AnalysisProperties(null, null, null, null, 15, null, null);
    }

    /** Moves every stored expiry back by {@code downtime}, as if the snapshot had been written that long ago. */
    private void ageSnapshot(Path file, Duration downtime) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());

        for (String cache : List.of("masterHistory", "results")) {
            for (JsonNode e : root.get(cache))
                ((ObjectNode)e).put("expiresAt", e.get("expiresAt").asLong() - downtime.toMillis());
        }

        mapper.writeValue(file.toFile(), root);
    }

    @Test
    void restoresResultsAndHistoryAcrossInstances(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("analysis.json");

        AnalysisCache first = new AnalysisCache(props(), mapper);
        AnalysisResult result = new AnalysisResult(42, 100L, "pull/42/head", System.currentTimeMillis(),
            List.of(new TestVerdict(7L, "TestA", "SuiteX", 200L, "Suite X", "301", true, false, "blocker", "FFF", 3)),
            List.of(),
            List.of(new TestVerdict(8L, "TestB", "SuiteX", 200L, "Suite X", "302", false, false, "pre-existing", "", 0)),
            List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0, 0);
        first.putResult(100L, result);
        first.history(7L, "SuiteX", () -> MASTER_JDK21_BREAK);

        first.saveTo(file);

        AnalysisCache reloaded = new AnalysisCache(props(), mapper);
        reloaded.loadFrom(file);

        assertThat(reloaded.peekResult(100L)).contains(result);
        // The loader must NOT run: proving the stats came back from disk, not a recompute.
        RunHistory restored = reloaded.history(7L, "SuiteX", () -> {
            throw new AssertionError("history should have been restored from the snapshot");
        });
        assertThat(restored).isEqualTo(MASTER_JDK21_BREAK);
        assertThat(reloaded.historyOf(7L, "SuiteY")).as("another suite's history of the same test").isEmpty();
    }

    /**
     * History used to be keyed by test id alone, which mixed every suite the test runs in: such an
     * entry cannot be split back per suite, so it is dropped, and the rest of the snapshot still loads.
     */
    @Test
    void historyKeyedByTestIdAloneIsDroppedOnLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("analysis.json");
        AnalysisCache first = new AnalysisCache(props(), mapper);
        first.putResult(100L, new AnalysisResult(13335, 100L, "pull/13335/head", System.currentTimeMillis(),
            List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0, 0));
        first.saveTo(file);

        ObjectNode old = ((ObjectNode) mapper.readTree(file.toFile())).retain("results", "rules");
        old.set("history", mapper.readTree("[{\"key\":5272433775095107011,\"value\":{\"runs\":100,\"fails\":9},"
            + "\"expiresAt\":" + (System.currentTimeMillis() + 60_000) + "}]"));
        mapper.writeValue(file.toFile(), old);

        AnalysisCache reloaded = new AnalysisCache(props(), mapper);
        reloaded.loadFrom(file);

        assertThat(reloaded.peekResult(100L)).isPresent();
        assertThat(reloaded.historyCount()).isZero();
    }

    /**
     * The previous release kept per suite only how many master runs there were and how many failed. That
     * cannot tell a JDK 21 failure from a JDK 17 one, or a recent failure from an old one, so it is
     * re-fetched; results from the previous rules are dropped, and the snapshot still loads.
     */
    @Test
    void historyCountsWithoutOrderOrJdkAreRefetched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("analysis.json");
        long expiresAt = System.currentTimeMillis() + 60_000;
        mapper.writeValue(file.toFile(), mapper.readTree("{\"suiteHistory\":[{\"key\":{\"testId\":"
            + "-1661331956011831017,\"buildTypeId\":\"IgniteTests24Java8_Snapshots8\"},\"value\":{\"runs\":100,"
            + "\"fails\":1},\"expiresAt\":" + expiresAt + "}],\"results\":[{\"key\":9391879,\"value\":"
            + mapper.writeValueAsString(new AnalysisResult(13654, 9391879L, "pull/13654/head", 1791307406924L,
                List.of(), List.of(), List.of(), List.of(), List.of(), 22, 125, false, 0, true, 9392791L, 0, 0, 0, 0))
            + ",\"expiresAt\":" + expiresAt + "}],\"rules\":6}"));

        AnalysisCache reloaded = new AnalysisCache(props(), mapper);
        reloaded.loadFrom(file);

        assertThat(reloaded.historyCount()).isZero();
        assertThat(reloaded.peekResult(9391879L)).as("a verdict made by the previous rules").isEmpty();
    }

    @Test
    void historyOutlivedByDowntimeIsRefetchedWhileResultsRevive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("analysis.json");
        long testId = 7L;
        long runAll = 9389046L;

        AnalysisCache before = new AnalysisCache(props(), mapper);
        AnalysisResult result = new AnalysisResult(13335, runAll, "pull/13335/head", System.currentTimeMillis(),
            List.of(), List.of(),
            List.of(new TestVerdict(testId, "TestA", "SuiteX", 200L, "Suite X", "301", false, false,
                "pre-existing: fails 1/100 on master", "F", 1)),
            List.of(), List.of(), 0, 0, false, 0, false, 0, 0, 0, 0, 0);
        before.putResult(runAll, result);
        before.history(testId, "SuiteX", () -> MASTER_JDK21_BREAK);
        before.saveTo(file);
        ageSnapshot(file, Duration.ofHours(3));

        AnalysisCache after = new AnalysisCache(props(), mapper);
        after.loadFrom(file);

        assertThat(after.historyOf(testId, "SuiteX")).as("master history outlived by the downtime").isEmpty();
        RunHistory refetched = new RunHistory("PPP", "aaa", List.of(JDK17), List.of());
        assertThat(after.history(testId, "SuiteX", () -> refetched)).isEqualTo(refetched);
        assertThat(after.peekResult(runAll)).as("a build's result never changes").contains(result);
    }

    @Test
    void loadFromMissingFileIsNoOp(@TempDir Path dir) throws Exception {
        AnalysisCache cache = new AnalysisCache(props(), mapper);

        cache.loadFrom(dir.resolve("does-not-exist.json"));

        assertThat(cache.peekResult(1L)).isEmpty();
    }

    @Test
    void ttlCacheExportDropsExpiredAndImportRevives() throws Exception {
        TtlCache<Long, String> cache = new TtlCache<>(60);
        cache.put(1L, "fresh");
        assertThat(cache.export()).hasSize(1);

        Thread.sleep(80);
        assertThat(cache.export()).isEmpty();

        // Importing revives even a formally expired entry with a fresh TTL: snapshot entries are
        // keyed by immutable identities, so a redeploy must not cold-start what it already knows.
        TtlCache<Long, String> target = new TtlCache<>(60);
        target.importAll(List.of(new TtlCache.Snapshot<>(9L, "revived", System.currentTimeMillis() - 1)));
        assertThat(target.peek(9L)).contains("revived");
    }

    @Test
    void ttlCacheImportUnexpiredKeepsStoredExpiryAndDropsExpired() {
        long now = System.currentTimeMillis();
        long hourLeft = now + Duration.ofHours(1).toMillis();
        TtlCache<Long, String> cache = new TtlCache<>(Duration.ofHours(2).toMillis());

        cache.importUnexpired(List.of(
            new TtlCache.Snapshot<>(1L, "an hour old", hourLeft),
            new TtlCache.Snapshot<>(2L, "three hours old", now - Duration.ofHours(1).toMillis())));

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.export()).containsExactly(new TtlCache.Snapshot<>(1L, "an hour old", hourLeft));
    }

    @Test
    void touchExtendsTtl() throws Exception {
        TtlCache<Long, String> cache = new TtlCache<>(100);
        cache.put(1L, "v");
        Thread.sleep(60);
        cache.touch(1L); // without the touch the entry would die at 100ms
        Thread.sleep(60);
        assertThat(cache.peek(1L)).contains("v");
    }
}
