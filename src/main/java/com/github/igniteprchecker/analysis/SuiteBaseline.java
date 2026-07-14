package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * How many tests each suite runs on master — the yardstick for "this suite ran far fewer tests than
 * it should". TeamCity has its own metric for this, but on PR branches it compares against a stale
 * reference build (one from before a legitimate test-count change), so it both false-alarms and
 * misses; master is the honest baseline.
 *
 * <p>Refreshed lazily (any analysis's token will do) at most every {@link #TTL_MS}, and persisted:
 * two TeamCity calls cover all ~150 suites, so every PR's check is effectively free.
 */
@Component
public class SuiteBaseline implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(SuiteBaseline.class);

    private static final long TTL_MS = 6 * 3600_000L;

    private final ObjectMapper mapper;
    private final TcClient tc;

    private volatile Map<String, Integer> counts = Map.of();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    public SuiteBaseline(ObjectMapper mapper, TcClient tc) {
        this.mapper = mapper;
        this.tc = tc;
    }

    /** Master test counts per suite (buildTypeId -> tests); refreshed in the background when stale. */
    public Map<String, Integer> counts(String token) {
        if (System.currentTimeMillis() - fetchedAt > TTL_MS)
            refresh(token);

        return counts;
    }

    /** A refresh failure is never fatal: the previous (or empty) baseline just keeps being used. */
    private synchronized void refresh(String token) {
        if (fetching || System.currentTimeMillis() - fetchedAt <= TTL_MS)
            return;

        fetching = true;
        try {
            Map<String, Integer> fresh = tc.masterSuiteTestCounts(token);
            if (!fresh.isEmpty()) {
                counts = fresh;
                fetchedAt = System.currentTimeMillis();
                log.info("suite baseline refreshed: {} suites from master", fresh.size());
            }
        }
        catch (RuntimeException e) {
            log.warn("suite baseline refresh failed: {}", e.toString());
        }
        finally {
            fetching = false;
        }
    }

    public int size() {
        return counts.size();
    }

    public long fetchedAt() {
        return fetchedAt;
    }

    @Override
    public String fileName() {
        return "suite-baseline.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        Snapshots.writeAtomic(mapper, file, new Persisted(fetchedAt, new HashMap<>(counts)));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);
        if (p.counts() != null && !p.counts().isEmpty()) {
            counts = new HashMap<>(p.counts());
            fetchedAt = p.fetchedAt();
        }
    }

    private record Persisted(long fetchedAt, Map<String, Integer> counts) {
    }
}
