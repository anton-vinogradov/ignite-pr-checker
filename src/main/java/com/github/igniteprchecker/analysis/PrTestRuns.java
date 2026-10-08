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
import java.util.Set;
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
 * classes, shared with the analysis. A class the PR adds has no master history to look up. A PR with test classes
 * also gets Ignite's own check of its head for classes in no test suite ({@link AbandonedTestsCheck}), and, for a
 * class with no runs, which files changed since the run: one GitHub call for the head, one for the comparison. An
 * answer is kept for 15 minutes per PR and chain, the PR's files for as long; an answer about a chain still going,
 * or with Ignite's check still going or out of reach, for 2 minutes.
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

    /** How long the PR's head is taken as known: the answer and the verdict of one settle ask it twice in a row. */
    private static final long KEEP_HEAD_MS = 60_000L;

    /** What a class name is made of, and so what may go into TeamCity's pattern unescaped. */
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final GithubClient github;

    private final TcClient tc;

    private final AnalysisCache cache;

    private final AbandonedTestsCheck suites;

    private final TtlCache<Integer, List<GithubClient.PrFile>> files = new TtlCache<>(KEEP_MS);

    private final TtlCache<Integer, String> heads = new TtlCache<>(KEEP_HEAD_MS);

    private final TtlCache<Asked, PrTests> answers = new TtlCache<>(KEEP_MS);

    private final TtlCache<Asked, PrTests> briefAnswers = new TtlCache<>(KEEP_RUNNING_MS);

    public PrTestRuns(GithubClient github, TcClient tc, AnalysisCache cache) {
        this.github = github;
        this.tc = tc;
        this.cache = cache;
        this.suites = new AbandonedTestsCheck(github);
    }

    /**
     * How the PR's new and changed test classes ran in its RunAll chain {@code buildId}, which is still going when
     * {@code running}: an answer about it is not the one about the chain once it finished.
     */
    public PrTests of(String token, int pr, long buildId, boolean running) {
        Asked asked = new Asked(pr, buildId, running);
        Optional<PrTests> was = answers.peek(asked).or(() -> briefAnswers.peek(asked));
        if (was.isPresent())
            return was.get();

        Answer answer = lookUp(token, pr, buildId, running);
        if (answer.keep())
            (running || !answer.settled() ? briefAnswers : answers).put(asked, answer.tests());

        return answer.tests();
    }

    /**
     * What Ignite's abandoned-tests check of the PR's head says when it finds classes in no test suite; empty when it
     * finds none, says nothing yet, or the PR adds and changes no test class.
     */
    public Optional<AbandonedTestsCheck.Outcome> notInAnySuite(int pr) {
        try {
            if (files.get(pr, () -> github.prTestFiles(pr)).isEmpty())
                return Optional.empty();

            String head = head(pr);
            AbandonedTestsCheck.Outcome check = head == null ? null : suites.of(head);

            return check != null && check.state() == PrTests.SuiteCheck.State.FAILED ? Optional.of(check)
                : Optional.empty();
        }
        catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Sweeps out expired answers and file lists (see TtlCache.evictExpired). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        files.evictExpired();
        heads.evictExpired();
        answers.evictExpired();
        briefAnswers.evictExpired();
        suites.evictExpired();
    }

    private Answer lookUp(String token, int pr, long buildId, boolean running) {
        List<ClassFile> classes;
        try {
            classes = files.get(pr, () -> github.prTestFiles(pr)).stream().map(ClassFile::of).toList();
        }
        catch (RuntimeException e) {
            return new Answer(new PrTests(buildId, List.of(), "GitHub could not list the PR's files"), false, true);
        }
        if (classes.isEmpty())
            return new Answer(new PrTests(buildId, List.of(), null), true, true);

        String head = head(pr);
        AbandonedTestsCheck.Outcome check = head == null
            ? AbandonedTestsCheck.Outcome.unknown(null, null, "GitHub could not name the PR's head", false)
            : suites.of(head);

        List<String> names = classes.stream().map(ClassFile::simple).filter(n -> CLASS_NAME.matcher(n).matches())
            .distinct().toList();
        Optional<List<TcModel.TestOccurrence>> runs;
        try {
            runs = tc.testRunsOfClasses(token, buildId, names);
        }
        catch (RuntimeException e) {
            return new Answer(answer(buildId, classes, Map.of(), "TeamCity could not be asked how they ran", check,
                Map.of()), false, check.settled());
        }
        if (runs.isEmpty()) {
            return new Answer(answer(buildId, classes, Map.of(), "TeamCity does not answer how they ran", check,
                Map.of()), true, check.settled());
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
        Map<ClassFile, Boolean> changed = running ? Map.of()
            : changedSinceRun(token, buildId, head, unexplained(classes, byClass, check));

        return new Answer(answer(buildId, classes, byClass, note, check, changed), true, check.settled());
    }

    /** The PR's head commit; null when GitHub could not say. */
    private String head(int pr) {
        try {
            return heads.get(pr, () -> github.prHead(pr).headSha());
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The classes with no runs whose absence Ignite's check leaves to explain: it passed, or failed naming others. Any
     * other state of the check explains nothing, and asks nothing more of GitHub.
     */
    private static List<ClassFile> unexplained(List<ClassFile> classes, Map<ClassFile, List<PrTests.Run>> byClass,
        AbandonedTestsCheck.Outcome check) {
        if (check.state() != PrTests.SuiteCheck.State.PASSED && check.state() != PrTests.SuiteCheck.State.FAILED)
            return List.of();

        return classes.stream().filter(c -> !byClass.containsKey(c) && !c.notInSuite(check)).toList();
    }

    /**
     * Whether a commit after the run changed each class's file, by GitHub's comparison of the run's revision with the
     * head; a class left out when that cannot be told: a rebase or a force-push mixed master's changes in, or the
     * list was cut short before the file.
     */
    private Map<ClassFile, Boolean> changedSinceRun(String token, long buildId, String head,
        List<ClassFile> classes) {
        if (classes.isEmpty() || head == null)
            return Map.of();

        try {
            String built = tc.buildRevision(token, buildId).orElse(null);
            if (built == null)
                return Map.of();

            GithubClient.Changes changes = built.equals(head)
                ? new GithubClient.Changes(Set.of(), false, true) : github.changesBetween(built, head);
            if (changes.rewritten())
                return Map.of();

            Map<ClassFile, Boolean> changed = new LinkedHashMap<>();
            for (ClassFile c : classes) {
                if (changes.files().contains(c.path()))
                    changed.put(c, true);
                else if (changes.complete())
                    changed.put(c, false);
            }

            return changed;
        }
        catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static PrTests answer(long buildId, List<ClassFile> classes, Map<ClassFile, List<PrTests.Run>> byClass,
        String note, AbandonedTestsCheck.Outcome check, Map<ClassFile, Boolean> changed) {
        List<PrTests.TestClass> tested = classes.stream()
            .map(c -> new PrTests.TestClass(c.fqcn(), c.path(), c.added(), byClass.getOrDefault(c, List.of()).stream()
                .sorted(Comparator.comparing(PrTests.Run::name)).toList(), c.notInSuite(check), changed.get(c)))
            .toList();
        List<String> elsewhere = check.classes().stream()
            .filter(name -> classes.stream().noneMatch(c -> c.fqcn().equals(name))).toList();

        return new PrTests(buildId, tested, note,
            new PrTests.SuiteCheck(check.state(), check.sha(), check.url(), check.reason(), elsewhere));
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

    private static PrTests.Run run(TcModel.TestOccurrence o) {
        TcModel.BuildRef b = o.build();
        String suite = b == null ? null : b.buildTypeId();
        String suiteName = b != null && b.buildType() != null && b.buildType().name() != null ? b.buildType().name()
            : suite;

        return new PrTests.Run(o.test().id(), o.name(), suite, b == null ? 0 : b.id(), suiteName, o.id(), o.status(),
            o.duration() == null ? 0 : o.duration(), null);
    }

    /** One PR's question about one of its chains, still going or finished. */
    private record Asked(int pr, long buildId, boolean running) {
    }

    /**
     * An answer, and whether it may be kept: one that a GitHub or TeamCity error cut short is asked again. {@code
     * settled}: Ignite's check in it is finished, so it may be kept for long.
     */
    private record Answer(PrTests tests, boolean keep, boolean settled) {
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

        /** Whether Ignite's check finds this class in no test suite: a class nested in it is named apart. */
        boolean notInSuite(AbandonedTestsCheck.Outcome check) {
            return check.classes().contains(fqcn);
        }
    }
}
