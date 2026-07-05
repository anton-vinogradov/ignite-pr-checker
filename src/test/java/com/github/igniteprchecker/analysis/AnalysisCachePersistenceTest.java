package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisCachePersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static AnalysisProperties props() {
        return new AnalysisProperties(null, null, null, null, 15, null);
    }

    @Test
    void restoresResultsAndHistoryAcrossInstances(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("analysis.json");

        AnalysisCache first = new AnalysisCache(props(), mapper);
        AnalysisResult result = new AnalysisResult(42, 100L, "pull/42/head", System.currentTimeMillis(),
            List.of(new TestVerdict(7L, "TestA", "SuiteX", 200L, "Suite X", "301", true, "blocker", "FFF")),
            List.of(new TestVerdict(8L, "TestB", "SuiteX", 200L, "Suite X", "302", false, "pre-existing", "")),
            List.of(), 0, 0);
        first.putResult(100L, result);
        first.history(7L, () -> new HistoryStats(30, 1));

        first.saveTo(file);

        AnalysisCache reloaded = new AnalysisCache(props(), mapper);
        reloaded.loadFrom(file);

        assertThat(reloaded.peekResult(100L)).contains(result);
        // The loader must NOT run: proving the stats came back from disk, not a recompute.
        HistoryStats restored = reloaded.history(7L, () -> {
            throw new AssertionError("history should have been restored from the snapshot");
        });
        assertThat(restored).isEqualTo(new HistoryStats(30, 1));
    }

    @Test
    void loadFromMissingFileIsNoOp(@TempDir Path dir) throws Exception {
        AnalysisCache cache = new AnalysisCache(props(), mapper);

        cache.loadFrom(dir.resolve("does-not-exist.json"));

        assertThat(cache.peekResult(1L)).isEmpty();
    }

    @Test
    void ttlCacheExportDropsExpiredAndImportKeepsExpiry() throws Exception {
        TtlCache<Long, String> cache = new TtlCache<>(60);
        cache.put(1L, "fresh");
        assertThat(cache.export()).hasSize(1);

        Thread.sleep(80);
        assertThat(cache.export()).isEmpty();

        // Importing an already-expired entry is a no-op.
        TtlCache<Long, String> target = new TtlCache<>(60);
        target.importAll(List.of(new TtlCache.Snapshot<>(9L, "stale", System.currentTimeMillis() - 1)));
        assertThat(target.peek(9L)).isEmpty();
    }
}
