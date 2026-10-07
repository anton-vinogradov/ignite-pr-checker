package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * What TeamCity said about finished builds and about PR branches, kept so a recompute fetches only what is
 * new. A recompute used to read it all again: on ci2 an hour of one or two users cost about 7,500 calls, a
 * third of them the failed tests of builds that had long finished, and PR 13583 asked for the branch runs of
 * its 489 watched tests on every recompute.
 *
 * <p>A finished build does not change, so its failed tests, and the suites of a finished chain, are kept by
 * build id. A PR branch's runs of a test in a suite change only when a build of that suite finishes on the
 * branch: one question per compute (see {@link #branch}) tells which suites did since the branch was last
 * checked, and the runs of every other suite are reused.
 *
 * <p>Nothing is kept longer than {@link #TTL_MS} after it was fetched, however often it is reused: TeamCity
 * leaves muted failures out of what is fetched here, and a test muted later must drop out within that time.
 */
final class BuildFacts {
    /** How long a fetched answer is kept at most. */
    static final long TTL_MS = 3 * 3_600_000L;

    private final TtlCache<Long, List<TcModel.TestOccurrence>> failedTests = new TtlCache<>(TTL_MS);

    private final TtlCache<Long, TcModel.Build> chains = new TtlCache<>(TTL_MS);

    private final TtlCache<TestKey, Checked<List<TcModel.TestOccurrence>>> testRuns = new TtlCache<>(TTL_MS);

    private final TtlCache<SuiteKey, Checked<TcModel.Build>> latestRuns = new TtlCache<>(TTL_MS);

    /** Per PR, the latest watermark its branch was checked against: where the next check starts. */
    private final ConcurrentMap<Integer, Long> checkedAt = new ConcurrentHashMap<>();

    /** The failed tests of a suite run, kept once the run has finished. */
    List<TcModel.TestOccurrence> failedTests(TcModel.Build run, Supplier<List<TcModel.TestOccurrence>> loader) {
        return finished(run) ? failedTests.get(run.id(), loader) : loader.get();
    }

    /** A chain with its suites, kept once the chain and every suite of it have finished. */
    TcModel.Build chain(long buildId, Supplier<TcModel.Build> loader) {
        Optional<TcModel.Build> kept = chains.peek(buildId);
        if (kept.isPresent())
            return kept.get();

        TcModel.Build chain = loader.get();
        if (chain != null && finished(chain) && (chain.snapshotDependencies() == null
            || chain.snapshotDependencies().build() == null
            || chain.snapshotDependencies().build().stream().allMatch(BuildFacts::finished)))
            chains.put(buildId, chain);

        return chain;
    }

    /**
     * The PR branch as one compute reads it. {@code watermarkAt} is the compute's branch watermark: every
     * build that finished before it is in whatever the compute fetches. {@code finishedSince} names the
     * suites with a build finished on the branch after a moment, or nothing when TeamCity can't tell.
     */
    Branch branch(int pr, long watermarkAt, LongFunction<Optional<Set<String>>> finishedSince) {
        return new Branch(pr, watermarkAt, finishedSince);
    }

    /** Sweeps out expired entries (see TtlCache.evictExpired). */
    void evictExpired() {
        failedTests.evictExpired();
        chains.evictExpired();
        testRuns.evictExpired();
        latestRuns.evictExpired();
        long oldest = System.currentTimeMillis() / 1000 - TTL_MS / 1000;
        checkedAt.values().removeIf(at -> at < oldest);
    }

    void clear() {
        failedTests.clear();
        chains.clear();
        testRuns.clear();
        latestRuns.clear();
        checkedAt.clear();
    }

    Saved export() {
        return new Saved(failedTests.export(), chains.export(), testRuns.export(), latestRuns.export(),
            new HashMap<>(checkedAt));
    }

    /** Takes back what a snapshot kept, each entry with the expiry it had. */
    void importUnexpired(Saved saved) {
        if (saved.failedTests() != null)
            failedTests.importUnexpired(saved.failedTests());
        if (saved.chains() != null)
            chains.importUnexpired(saved.chains());
        if (saved.testRuns() != null)
            testRuns.importUnexpired(saved.testRuns());
        if (saved.latestRuns() != null)
            latestRuns.importUnexpired(saved.latestRuns());
        if (saved.checkedAt() != null)
            saved.checkedAt().forEach((pr, at) -> checkedAt.merge(pr, at, Math::max));
    }

    private static boolean finished(TcModel.Build build) {
        return "finished".equalsIgnoreCase(build.state());
    }

    /**
     * One compute's view of a PR branch. Whatever it reuses was fetched by an earlier compute and checked
     * against the suites that finished a build since: it is the same answer TeamCity would give now.
     */
    final class Branch {
        private final int pr;

        private final long watermarkAt;

        private final LongFunction<Optional<Set<String>>> finishedSince;

        /** The check the reused answers rely on, made once, on the first lookup. */
        private volatile Check check;

        private Branch(int pr, long watermarkAt, LongFunction<Optional<Set<String>>> finishedSince) {
            this.pr = pr;
            this.watermarkAt = watermarkAt;
            this.finishedSince = finishedSince;
        }

        /** The test's runs in the suite on this branch: {@code loader} unless an earlier answer still holds. */
        List<TcModel.TestOccurrence> testRuns(long testId, String suite,
            Supplier<List<TcModel.TestOccurrence>> loader) {
            return lookup(testRuns, new TestKey(pr, testId, suite), suite, loader);
        }

        /** The suite's newest finished run on this branch, null when there is none. */
        TcModel.Build latestRun(String suite, Supplier<Optional<TcModel.Build>> loader) {
            return lookup(latestRuns, new SuiteKey(pr, suite), suite, () -> loader.get().orElse(null));
        }

        private <K, V> V lookup(TtlCache<K, Checked<V>> kept, K key, String suite, Supplier<V> loader) {
            Check c = check();
            Optional<Checked<V>> earlier = kept.peek(key);
            if (earlier.isPresent() && c.unmoved(suite, earlier.get().checkedAt())) {
                if (earlier.get().checkedAt() < watermarkAt)
                    kept.replace(key, new Checked<>(earlier.get().value(), watermarkAt));

                return earlier.get().value();
            }

            V value = loader.get();
            kept.put(key, new Checked<>(value, watermarkAt));

            return value;
        }

        private Check check() {
            Check c = check;
            if (c == null) {
                synchronized (this) {
                    c = check;
                    if (c == null)
                        check = c = makeCheck();
                }
            }

            return c;
        }

        /**
         * Asks which suites finished a build on the branch since it was last checked. Not asked when nothing
         * kept can be that old: past the TTL, every answer has been fetched again anyway.
         */
        private Check makeCheck() {
            long since = checkedAt.getOrDefault(pr, 0L);
            Optional<Set<String>> moved = Optional.empty();
            if (since > 0 && since * 1000 > System.currentTimeMillis() - TTL_MS) {
                try {
                    moved = finishedSince.apply(since);
                }
                catch (RuntimeException e) {
                    // TeamCity could not say: fetch everything, as if nothing had been kept.
                }
            }
            checkedAt.merge(pr, watermarkAt, Math::max);

            return new Check(since, moved.orElse(null));
        }
    }

    /**
     * What one compute knows about its branch: the suites with a build finished after {@code since}, or
     * null when unknown.
     */
    private record Check(long since, Set<String> moved) {
        /** Whether an answer checked at {@code at} still holds for the suite. */
        boolean unmoved(String suite, long at) {
            return moved != null && at >= since && !moved.contains(suite);
        }
    }

    /**
     * An answer about a PR branch and the watermark it holds up to: no build of its suite that finished
     * before {@code checkedAt} is missing from it.
     */
    record Checked<V>(V value, long checkedAt) {
    }

    record TestKey(int pr, long testId, String suite) {
    }

    record SuiteKey(int pr, String suite) {
    }

    /** What a snapshot keeps; any part missing from an older snapshot is simply fetched again. */
    record Saved(
        List<TtlCache.Snapshot<Long, List<TcModel.TestOccurrence>>> failedTests,
        List<TtlCache.Snapshot<Long, TcModel.Build>> chains,
        List<TtlCache.Snapshot<TestKey, Checked<List<TcModel.TestOccurrence>>>> testRuns,
        List<TtlCache.Snapshot<SuiteKey, Checked<TcModel.Build>>> latestRuns,
        Map<Integer, Long> checkedAt
    ) {
    }
}
