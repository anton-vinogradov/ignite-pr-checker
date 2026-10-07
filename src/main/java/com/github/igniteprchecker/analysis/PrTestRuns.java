package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.PrTests;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How the PR's own new and changed test classes ran in its RunAll: each test's status and duration there, and
 * whether master has any runs of it. TcpDiscoveryClientTopologyGapTest came in with PR 13327 taking 298 s and
 * passing once, and now fails 18 of 100 master runs, noise in 77 PRs; nothing showed a new test apart. Nothing here
 * makes a PR run anything again.
 *
 * <p>Asked when a PR is viewed: one GitHub call for the PR's files, one TeamCity call for the runs of its test
 * classes in the chain (more only for hundreds of classes), and the master history of up to 20 tests of the changed
 * classes, shared with the analysis. A class the PR adds has no master history to look up. An answer is kept for 15
 * minutes per PR and chain, the PR's files for as long; an answer about a chain still going, for 2 minutes.
 */
@Component
public class PrTestRuns {
    private static final long KEEP_MS = 15 * 60_000L;

    /**
     * How long an answer about a chain still going is kept: its suites go on finishing, and a live verdict is
     * recomputed after as long (BlockerAnalyzer.isStale).
     */
    private static final long KEEP_RUNNING_MS = 2 * 60_000L;

    /** The most tests of changed classes whose master history one answer looks up. */
    static final int MASTER_LOOKUPS = 20;

    /** What a class name is made of, and so what may go into TeamCity's pattern unescaped. */
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final GithubClient github;

    private final TcClient tc;

    private final AnalysisCache cache;

    private final TtlCache<Integer, List<GithubClient.PrFile>> files = new TtlCache<>(KEEP_MS);

    private final TtlCache<Asked, PrTests> answers = new TtlCache<>(KEEP_MS);

    private final TtlCache<Asked, PrTests> runningAnswers = new TtlCache<>(KEEP_RUNNING_MS);

    public PrTestRuns(GithubClient github, TcClient tc, AnalysisCache cache) {
        this.github = github;
        this.tc = tc;
        this.cache = cache;
    }

    /**
     * How the PR's new and changed test classes ran in its RunAll chain {@code buildId}, which is still going when
     * {@code running}: an answer about it is not the one about the chain once it finished.
     */
    public PrTests of(String token, int pr, long buildId, boolean running) {
        TtlCache<Asked, PrTests> kept = running ? runningAnswers : answers;
        Asked asked = new Asked(pr, buildId);
        Optional<PrTests> was = kept.peek(asked);
        if (was.isPresent())
            return was.get();

        Answer answer = lookUp(token, pr, buildId, running);
        if (answer.keep())
            kept.put(asked, answer.tests());

        return answer.tests();
    }

    /** Sweeps out expired answers and file lists (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        files.evictExpired();
        answers.evictExpired();
        runningAnswers.evictExpired();
    }

    private Answer lookUp(String token, int pr, long buildId, boolean running) {
        List<ClassFile> classes;
        try {
            classes = files.get(pr, () -> github.prTestFiles(pr)).stream().map(ClassFile::of).toList();
        }
        catch (RuntimeException e) {
            return new Answer(new PrTests(buildId, List.of(), "GitHub could not list the PR's files"), false);
        }
        if (classes.isEmpty())
            return new Answer(new PrTests(buildId, List.of(), null), true);

        List<String> names = classes.stream().map(ClassFile::simple).filter(n -> CLASS_NAME.matcher(n).matches())
            .distinct().toList();
        Optional<List<TcModel.TestOccurrence>> runs;
        try {
            runs = tc.testRunsOfClasses(token, buildId, names);
        }
        catch (RuntimeException e) {
            return new Answer(new PrTests(buildId, withRuns(classes, Map.of()),
                "TeamCity could not be asked how they ran"), false);
        }
        if (runs.isEmpty()) {
            return new Answer(new PrTests(buildId, withRuns(classes, Map.of()),
                "TeamCity does not answer how they ran"), true);
        }

        Map<ClassFile, List<PrTests.Run>> byClass = new LinkedHashMap<>();
        for (TcModel.TestOccurrence o : runs.get()) {
            if (o.name() == null || o.test() == null)
                continue;
            classes.stream().filter(c -> c.ran(o.name())).findFirst()
                .ifPresent(c -> byClass.computeIfAbsent(c, k -> new ArrayList<>()).add(run(o)));
        }

        List<String> notes = new ArrayList<>();
        if (running)
            notes.add("the RunAll is still going");
        if (runs.get().size() >= TcClient.CLASS_RUNS_MAX)
            notes.add("only the first " + TcClient.CLASS_RUNS_MAX + " runs were read");

        int toLookUp = withMasterRuns(token, byClass);
        if (toLookUp > MASTER_LOOKUPS)
            notes.add("master history was looked up for " + MASTER_LOOKUPS + " of " + toLookUp
                + " tests of changed classes");

        String note = notes.isEmpty() ? null : String.join("; ", notes);

        return new Answer(new PrTests(buildId, withRuns(classes, byClass), note), true);
    }

    /**
     * Fills in master's run count for the tests of changed classes, failed ones first, then the slowest, up to
     * {@link #MASTER_LOOKUPS} of them; returns how many there were to look up.
     */
    private int withMasterRuns(String token, Map<ClassFile, List<PrTests.Run>> byClass) {
        List<PrTests.Run> changed = byClass.entrySet().stream()
            .filter(e -> !e.getKey().added())
            .flatMap(e -> e.getValue().stream())
            .sorted(Comparator.comparing((PrTests.Run r) -> !"FAILURE".equals(r.status()))
                .thenComparing(Comparator.comparingLong(PrTests.Run::durationMs).reversed()))
            .toList();

        Map<PrTests.Run, PrTests.Run> looked = new LinkedHashMap<>();
        for (PrTests.Run r : changed.subList(0, Math.min(MASTER_LOOKUPS, changed.size())))
            looked.put(r, withMasterRuns(r, masterRuns(token, r)));

        byClass.replaceAll((c, runs) -> runs.stream().map(r -> looked.getOrDefault(r, r)).toList());

        return changed.size();
    }

    /** How many master runs of the test are on record, null when TeamCity can't say. */
    private Integer masterRuns(String token, PrTests.Run r) {
        try {
            return cache.history(r.testId(), r.suite(),
                () -> RunHistory.ofMaster(tc.getBaseBranchHistory(token, r.testId(), r.suite()))).all().runs();
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static PrTests.Run withMasterRuns(PrTests.Run r, Integer masterRuns) {
        return new PrTests.Run(r.testId(), r.name(), r.suite(), r.suiteBuildId(), r.suiteName(), r.occurrenceId(),
            r.status(), r.durationMs(), masterRuns);
    }

    private static List<PrTests.TestClass> withRuns(List<ClassFile> classes,
        Map<ClassFile, List<PrTests.Run>> byClass) {
        return classes.stream()
            .map(c -> new PrTests.TestClass(c.fqcn(), c.path(), c.added(), byClass.getOrDefault(c, List.of()).stream()
                .sorted(Comparator.comparing(PrTests.Run::name)).toList()))
            .toList();
    }

    private static PrTests.Run run(TcModel.TestOccurrence o) {
        TcModel.BuildRef b = o.build();
        String suite = b == null ? null : b.buildTypeId();
        String suiteName = b != null && b.buildType() != null && b.buildType().name() != null ? b.buildType().name()
            : suite;

        return new PrTests.Run(o.test().id(), o.name(), suite, b == null ? 0 : b.id(), suiteName, o.id(), o.status(),
            o.duration() == null ? 0 : o.duration(), null);
    }

    /** One PR's question about one of its chains. */
    private record Asked(int pr, long buildId) {
    }

    /** An answer, and whether it may be kept: one that a GitHub or TeamCity error cut short is asked again. */
    private record Answer(PrTests tests, boolean keep) {
    }

    /**
     * A test class as its file names it: the package is the path under {@code java/}, when the path has one, as in
     * {@code modules/core/src/test/java/org/apache/ignite/FooTest.java}.
     */
    private record ClassFile(String fqcn, String simple, String path, boolean added) {
        static ClassFile of(GithubClient.PrFile f) {
            String noExt = f.path().substring(0, f.path().length() - ".java".length());
            int java = noExt.lastIndexOf("/java/");
            String fqcn = (java >= 0 ? noExt.substring(java + "/java/".length())
                : noExt.substring(noExt.lastIndexOf('/') + 1)).replace('/', '.');

            return new ClassFile(fqcn, fqcn.substring(fqcn.lastIndexOf('.') + 1), f.path(), "added".equals(f.status()));
        }

        /**
         * Whether TeamCity's test name, {@code "SomeTestSuite: org.apache.ignite.FooTest.testBar[param]"}, is a test
         * of this class: a nested class's too, and one named without its package.
         */
        boolean ran(String testName) {
            int suite = testName.indexOf(": ");
            String test = suite >= 0 ? testName.substring(suite + 2) : testName;

            return test.startsWith(fqcn + ".") || test.startsWith(fqcn + "$") || test.startsWith(simple + ".");
        }
    }
}
