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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
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
 * also gets Ignite's own check of its head for classes in no test suite ({@link AbandonedTestsCheck}). For a class
 * with no runs, what the run's revision had of it: GitHub's comparison of that revision with the head, and Ignite's
 * check of it unless the class is new since. How the classes ran is kept for 15 minutes per PR and chain, for 2 while
 * the chain goes, the PR's files and a comparison for 15 minutes, the head for one, and Ignite's checks for as long as
 * AbandonedTestsCheck keeps them.
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

    /** GitHub's statuses of a file at a path the base did not have it at. */
    private static final Set<String> NEW_AT_PATH = Set.of("added", "renamed", "copied");

    /** GitHub's statuses of a file the base had at the same path. */
    private static final Set<String> KEPT_AT_PATH = Set.of("modified", "changed", "unchanged");

    private final GithubClient github;

    private final TcClient tc;

    private final AnalysisCache cache;

    private final AbandonedTestsCheck suites;

    private final TtlCache<Integer, List<GithubClient.PrFile>> files;

    private final TtlCache<Integer, String> heads;

    private final TtlCache<Asked, Ran> answers;

    private final TtlCache<Asked, Ran> runningAnswers;

    private final TtlCache<Between, GithubClient.Changes> changes;

    @Autowired
    public PrTestRuns(GithubClient github, TcClient tc, AnalysisCache cache) {
        this(github, tc, cache, System::currentTimeMillis);
    }

    PrTestRuns(GithubClient github, TcClient tc, AnalysisCache cache, LongSupplier nowMs) {
        this.github = github;
        this.tc = tc;
        this.cache = cache;
        this.suites = new AbandonedTestsCheck(github, nowMs);
        this.files = new TtlCache<>(KEEP_MS, nowMs);
        this.heads = new TtlCache<>(KEEP_HEAD_MS, nowMs);
        this.answers = new TtlCache<>(KEEP_MS, nowMs);
        this.runningAnswers = new TtlCache<>(KEEP_RUNNING_MS, nowMs);
        this.changes = new TtlCache<>(KEEP_MS, nowMs);
    }

    /**
     * How the PR's new and changed test classes ran in its RunAll chain {@code buildId}, which is still going when
     * {@code running}: an answer about it is not the one about the chain once it finished.
     */
    public PrTests of(String token, int pr, long buildId, boolean running) {
        TtlCache<Asked, Ran> kept = running ? runningAnswers : answers;
        Asked asked = new Asked(pr, buildId);
        Ran ran = kept.peek(asked).orElseGet(() -> {
            Ran now = lookUp(token, pr, buildId, running);
            if (now.keep())
                kept.put(asked, now);

            return now;
        });
        if (ran.classes().isEmpty())
            return new PrTests(buildId, List.of(), ran.note());

        String head = head(pr);
        AbandonedTestsCheck.Outcome check = head == null
            ? AbandonedTestsCheck.Outcome.unknown(null, null, "GitHub could not name the PR's head", false)
            : suites.of(head);
        Map<ClassFile, PrTests.AtRun> atRun = running || ran.byClass() == null ? Map.of()
            : atRun(token, buildId, head, unexplained(ran, check));

        return answer(buildId, ran, check, atRun);
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
        runningAnswers.evictExpired();
        changes.evictExpired();
        suites.evictExpired();
    }

    private Ran lookUp(String token, int pr, long buildId, boolean running) {
        List<ClassFile> classes;
        try {
            classes = files.get(pr, () -> github.prTestFiles(pr)).stream().map(ClassFile::of).toList();
        }
        catch (RuntimeException e) {
            return new Ran(List.of(), null, "GitHub could not list the PR's files", false);
        }
        if (classes.isEmpty())
            return new Ran(classes, Map.of(), null, true);

        List<String> names = classes.stream().map(ClassFile::simple).filter(n -> CLASS_NAME.matcher(n).matches())
            .distinct().toList();
        Optional<List<TcModel.TestOccurrence>> runs;
        try {
            runs = tc.testRunsOfClasses(token, buildId, names);
        }
        catch (RuntimeException e) {
            return new Ran(classes, null, "TeamCity could not be asked how they ran", false);
        }
        if (runs.isEmpty())
            return new Ran(classes, null, "TeamCity does not answer how they ran", true);

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

        return new Ran(classes, byClass, notes.isEmpty() ? null : String.join("; ", notes), true);
    }

    /** The PR's head commit; null when GitHub could not say. */
    private String head(int pr) {
        try {
            return heads.get(pr, () -> Objects.requireNonNull(github.prOutcome(pr).headSha(), "head"));
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The classes with no runs whose absence Ignite's check leaves to explain: it passed, or failed naming others. Any
     * other state of the check explains nothing, and asks nothing more of GitHub.
     */
    private static List<ClassFile> unexplained(Ran ran, AbandonedTestsCheck.Outcome check) {
        if (!check.decided())
            return List.of();

        return ran.classes().stream().filter(c -> !ran.byClass().containsKey(c) && !c.notInSuite(check)).toList();
    }

    /**
     * What the run's revision had of each class: by GitHub's comparison of it with the head, a class new under its
     * name since; by Ignite's check of it, asked only for the others, a class in no suite then, or one the check
     * passed. A class is left out when that cannot be told: after a rebase or a force-push the comparison holds
     * master's changes too, a list cut short may miss the file, and the check of that revision may tell nothing.
     */
    private Map<ClassFile, PrTests.AtRun> atRun(String token, long buildId, String head, List<ClassFile> classes) {
        if (classes.isEmpty() || head == null)
            return Map.of();

        try {
            String built = cache.revision(buildId, () -> tc.buildRevision(token, buildId).orElse(""));
            if (built.isEmpty())
                return Map.of();

            GithubClient.Changes since = built.equals(head) ? GithubClient.Changes.NONE
                : changes.get(new Between(built, head), () -> github.changesBetween(built, head));
            Map<ClassFile, PrTests.AtRun> at = new LinkedHashMap<>();
            List<ClassFile> there = new ArrayList<>();
            for (ClassFile c : classes) {
                if (c.newSince(since))
                    at.put(c, PrTests.AtRun.ABSENT);
                else
                    there.add(c);
            }
            if (there.isEmpty())
                return at;

            AbandonedTestsCheck.Outcome then = suites.of(built);
            for (ClassFile c : there) {
                if (c.notInSuite(then))
                    at.put(c, PrTests.AtRun.IN_NO_SUITE);
                else if (then.decided() && c.keptSince(since))
                    at.put(c, PrTests.AtRun.PASSED_CHECK);
            }

            return at;
        }
        catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static PrTests answer(long buildId, Ran ran, AbandonedTestsCheck.Outcome check,
        Map<ClassFile, PrTests.AtRun> atRun) {
        Map<ClassFile, List<PrTests.Run>> byClass = ran.byClass() == null ? Map.of() : ran.byClass();
        List<PrTests.TestClass> tested = ran.classes().stream()
            .map(c -> new PrTests.TestClass(c.fqcn(), c.path(), c.added(), byClass.getOrDefault(c, List.of()).stream()
                .sorted(Comparator.comparing(PrTests.Run::name)).toList(), c.notInSuite(check), atRun.get(c)))
            .toList();
        List<String> elsewhere = check.classes().stream()
            .filter(name -> ran.classes().stream().noneMatch(c -> c.fqcn().equals(name))).toList();

        return new PrTests(buildId, tested, ran.note(),
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

    /** One PR's question about one of its chains. */
    private record Asked(int pr, long buildId) {
    }

    /**
     * How the PR's test classes ran in a chain: {@code byClass} holds the runs, null when TeamCity did not say;
     * {@code note} says what is missing. {@code keep}: no GitHub or TeamCity error cut it short, so it may be kept.
     */
    private record Ran(List<ClassFile> classes, Map<ClassFile, List<PrTests.Run>> byClass, String note,
        boolean keep) {
    }

    /** The revision a chain ran on, and the PR's head. */
    private record Between(String built, String head) {
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

        /** Whether the file came to its path after the base of {@code since}: added, or renamed or copied there. */
        boolean newSince(GithubClient.Changes since) {
            return !since.rewritten() && NEW_AT_PATH.contains(since.files().getOrDefault(path, ""));
        }

        /**
         * Whether the base of {@code since} had the file at its path: GitHub lists it as changed there, or leaves it
         * out of a list that is complete.
         */
        boolean keptSince(GithubClient.Changes since) {
            if (since.rewritten())
                return false;

            String status = since.files().get(path);

            return status == null ? since.complete() : KEPT_AT_PATH.contains(status);
        }
    }
}
