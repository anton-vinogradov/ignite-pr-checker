package com.github.igniteprchecker.analysis;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Groups a run's blockers by failure signature (the normalised first line of the failure message),
 * collapsing hundreds of failed tests into a handful of root causes — e.g. one codegen break showing
 * up as the same NoClassDefFoundError in 200 tests. Fetching every failure message is the expensive
 * part, so big runs are sampled ({@link #SAMPLE_CAP} blockers, taken in turn from each suite) and the
 * result is cached for the blockers it was computed for. Blockers without a message, or whose message
 * could not be read, are not a cause: they are listed apart.
 */
@Component
public class CauseClusters {
    private static final int SAMPLE_CAP = 80;

    /** Runs asked for their start date, for the note on blockers TeamCity no longer has a message for. */
    private static final int MAX_DATED_RUNS = 5;

    private static final Pattern SEPARATOR = Pattern.compile("[-=\\s]*(Std(out|err):?)?[-=\\s]*");

    private static final Pattern TIMED_OUT_TEST = Pattern.compile("\\s*\\[test=[^\\]]*\\]");

    private static final Pattern EXCEPTION_PREFIX =
        Pattern.compile("^(?:class\\s+)?(?:[A-Za-z_$][\\w$]*\\.)+([A-Z][\\w$]*)\\s*(?::\\s*(.*))?$");

    private static final Pattern AGENT_WORK_DIR = Pattern.compile("\\S*?[\\\\/]work[\\\\/][0-9a-f]{8,}[\\\\/]");

    /** Messages that fit failures of any cause; the failing line of the test tells such failures apart. */
    private static final Pattern GENERIC = Pattern.compile("(?i)null|[\\w$]*(?:Error|Exception|Failure)"
        + "|expected:<[^>]{0,40}> but was:<[^>]{0,40}>|expected: .{0,40}");

    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:[\\w.$@-]+/)?([^\\s(]+)\\s*\\(");

    private static final List<String> FRAMEWORK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
        "org.junit.", "junit.", "org.hamcrest.", "org.assertj.", "org.opentest4j.", "org.mockito.",
        "org.apache.ignite.testframework.", "o.a.i.testframework.", "org.apache.maven.", "org.codehaus.",
        "System.", "NUnit.", "Microsoft.");

    private final TcClient tc;
    private final FailureDetails failures;
    private final ExecutorService pool;
    private final TtlCache<Key, Result> cache = new TtlCache<>(15 * 60_000L);

    public CauseClusters(TcClient tc, FailureDetails failures,
        @Qualifier("causesExecutor") ExecutorService pool) {
        this.tc = tc;
        this.failures = failures;
        this.pool = pool;
    }

    /** Sweeps out expired cluster entries (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        cache.evictExpired();
    }

    /**
     * Clusters for a run's blockers, cached until the build or its blockers change. A result with messages
     * TeamCity did not answer for is not kept: asking again reads those, and FailureDetails has the rest.
     */
    public Result clusters(String token, AnalysisResult res) {
        Key key = Key.of(res);

        return cache.peek(key).orElseGet(() -> {
            Result r = compute(token, res);
            if (r.unread().isEmpty())
                cache.put(key, r);

            return r;
        });
    }

    /** The clusters for these blockers if someone already asked for them; never fetches a message. */
    public Optional<Result> cached(AnalysisResult res) {
        return cache.peek(Key.of(res));
    }

    private Result compute(String token, AnalysisResult res) {
        List<TestVerdict> blockers = res.blockers();
        List<TestVerdict> sample = acrossSuites(blockers, SAMPLE_CAP);

        List<Callable<Hit>> tasks = sample.stream()
            .<Callable<Hit>>map(v -> () -> {
                Member member = new Member(v.testId(), v.suite());
                try {
                    String details = v.occurrenceId() == null ? null : failures.of(token, v.occurrenceId());
                    String sig = details == null || details.isBlank() ? null : signature(details);

                    return new Hit(member, v.suiteBuildId(), sig, false);
                }
                catch (RuntimeException e) {
                    return new Hit(member, v.suiteBuildId(), null, true); // one missing message must not sink the rest
                }
            })
            .toList();

        Map<String, List<Member>> bySignature = new LinkedHashMap<>();
        List<Member> noMessage = new ArrayList<>();
        List<Member> unread = new ArrayList<>();
        Set<Long> silentRuns = new LinkedHashSet<>();
        for (Hit h : Parallel.run(pool, tasks)) {
            if (h.unread())
                unread.add(h.member());
            else if (h.signature() == null) {
                noMessage.add(h.member());
                silentRuns.add(h.suiteBuildId());
            }
            else
                bySignature.computeIfAbsent(h.signature(), k -> new ArrayList<>()).add(h.member());
        }

        List<Cluster> clusters = bySignature.entrySet().stream()
            .map(e -> new Cluster(e.getKey(), e.getValue().size(), List.copyOf(e.getValue())))
            .sorted(Comparator.comparingInt(Cluster::count).reversed())
            .toList();

        return new Result(blockers.size(), sample.size(), clusters, noMessage, unread, startTimes(token, silentRuns));
    }

    /** Up to {@code cap} blockers, one from each suite in turn, so a big broken suite cannot fill the sample. */
    static List<TestVerdict> acrossSuites(List<TestVerdict> blockers, int cap) {
        Map<String, Deque<TestVerdict>> bySuite = new LinkedHashMap<>();
        for (TestVerdict v : blockers)
            bySuite.computeIfAbsent(String.valueOf(v.suite()), k -> new ArrayDeque<>()).add(v);

        List<TestVerdict> out = new ArrayList<>();
        while (out.size() < cap && !bySuite.isEmpty()) {
            for (Iterator<Deque<TestVerdict>> it = bySuite.values().iterator(); it.hasNext() && out.size() < cap; ) {
                Deque<TestVerdict> suite = it.next();
                out.add(suite.poll());
                if (suite.isEmpty())
                    it.remove();
            }
        }

        return out;
    }

    /** When each of these runs started, in epoch seconds; a run TeamCity did not answer for is left out. */
    private Map<Long, Long> startTimes(String token, Set<Long> runs) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (long id : runs.stream().filter(id -> id > 0).limit(MAX_DATED_RUNS).toList()) {
            try {
                long at = TcDates.epochSeconds(tc.getBuildState(token, id).startDate());
                if (at > 0)
                    out.put(id, at);
            }
            catch (RuntimeException e) {
                // the date only words the note; the blockers are listed without it
            }
        }

        return out;
    }

    /**
     * The failure's message, normalised: without the exception class in front, the test name of a timeout,
     * the agent's work directory, and numbers, hashes and uuids. A message that fits any failure (a bare
     * AssertionError, "expected:&lt;false&gt; but was:&lt;true&gt;") gets the first frame of the stack that
     * is not test or JDK framework, so different checks do not read as one cause.
     */
    static String signature(String details) {
        String head = FailureOutput.head(details);
        String line = head.strip().lines()
            .map(String::strip)
            .filter(l -> !l.isBlank())
            .filter(l -> !SEPARATOR.matcher(l).matches())
            .findFirst().orElse("");

        line = withoutExceptionClass(TIMED_OUT_TEST.matcher(line).replaceAll(""));
        line = AGENT_WORK_DIR.matcher(line).replaceAll("")
            .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
            .replaceAll("0x[0-9a-fA-F]+", "<hex>")
            .replaceAll("\\d+", "N");
        if (line.length() > 120)
            line = line.substring(0, 120) + "…";

        if (line.isEmpty() || GENERIC.matcher(line).matches()) {
            String frame = firstOwnFrame(head);
            if (frame != null)
                return (line.isEmpty() ? "" : line + " ") + "at " + frame;
        }

        return line.isEmpty() ? "(empty failure message)" : line;
    }

    /** "Sequence contains no elements" of "System.InvalidOperationException : Sequence contains no elements". */
    private static String withoutExceptionClass(String line) {
        Matcher m = EXCEPTION_PREFIX.matcher(line);
        if (!m.matches())
            return line;

        return m.group(2) == null || m.group(2).isBlank() ? m.group(1) : m.group(2).strip();
    }

    /** "Class.method" of the first stack frame outside the JDK and the test frameworks, or null. */
    private static String firstOwnFrame(String head) {
        return head.lines()
            .map(FRAME::matcher)
            .filter(Matcher::find)
            .map(m -> m.group(1).replaceAll("\\[[^\\]]*\\]", ""))
            .filter(name -> FRAMEWORK.stream().noneMatch(name::startsWith))
            .map(name -> {
                String[] parts = name.split("\\.");

                return parts.length < 2 ? name : parts[parts.length - 2] + "." + parts[parts.length - 1];
            })
            .findFirst().orElse(null);
    }

    /** What a cached result was computed for: the build and every blocker's test, suite and occurrence. */
    private record Key(long buildId, Set<String> blockers) {
        static Key of(AnalysisResult res) {
            return new Key(res.buildId(), res.blockers().stream()
                .map(v -> v.testId() + "@" + v.suite() + "@" + v.occurrenceId())
                .collect(Collectors.toUnmodifiableSet()));
        }
    }

    /** One root cause: the shared failure signature and the blockers that hit it. */
    public record Cluster(String signature, int count, List<Member> tests) {
    }

    /**
     * A blocker in a cluster: its test and the suite it failed in. One test can be a blocker in several
     * suites, each failing its own way, so the test id alone can't say which of them hit this cause.
     */
    public record Member(@JsonFormat(shape = JsonFormat.Shape.STRING) long testId, String suite) {
    }

    /** One sampled blocker: its signature, or null when it has no message or the message could not be read. */
    private record Hit(Member member, long suiteBuildId, String signature, boolean unread) {
    }

    /**
     * Clustering outcome: total blockers, how many were sampled for messages, the clusters by size, the
     * sampled blockers TeamCity has no message for and those whose message could not be read, and when the
     * runs of the message-less ones started (suite build id to epoch seconds, as far as known).
     */
    public record Result(int total, int sampled, List<Cluster> clusters, List<Member> noMessage, List<Member> unread,
        Map<Long, Long> runStartedAt) {
    }
}
