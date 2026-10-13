package com.github.igniteprchecker.suitefix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AbandonedTestsCheck;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * Adds the test classes of an author's own PR that no test suite runs to suites, for the authors who switched "Add
 * my PRs' tests to a suite" on in ⚙. Ignite's own check finds such classes on every push ({@link
 * AbandonedTestsCheck}), and the author had to push again to fix it. Every 5 minutes the sweep looks at the open PRs
 * of these authors: when the check of a PR's head names classes of the PR's own files, Claude picks a suite of the
 * class's module for each ({@link SuiteChooser}), and one commit from the author's account lists each next to a class
 * the suite already lists ({@link TestSuites}). A comment in the PR says what went where and why, and gets the answer
 * of Ignite's check of that commit once it is in. Each head commit gets one attempt, whatever came of it.
 */
@Component
public class SuiteFixService implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(SuiteFixService.class);

    private static final long SWEEP_MS = 5 * 60_000L;

    /** The most classes one attempt adds: each costs a call to Claude. */
    static final int MAX_CLASSES = 10;

    /** Ignite's core module has some 130 files named as suites. */
    static final int MAX_SUITE_FILES = 400;

    private static final int MAX_FILE_BYTES = 512 * 1024;

    /** All suite files of a module downloaded together, at most: the service runs in a 512 MB heap. */
    private static final long MAX_SUITES_BYTES = 8_000_000;

    /** How long the comment waits for Ignite's check of the commit before it stops waiting. */
    private static final long FOLLOW_MS = 24 * 3600_000L;

    /** How long an attempt is remembered: long past any head anyone still pushes over. */
    private static final long KEEP_MS = 30 * 24 * 3600_000L;

    /** Heads whose check names no class of their PR, remembered so the sweep does not ask again. */
    private static final int QUIET_MAX = 5000;

    /** What Claude's reason may not hold: anything but words and plain punctuation, so no markup, link or mention. */
    private static final Pattern NOT_PLAIN = Pattern.compile("[^\\p{L}\\p{N} .,;'()-]");

    private static final int REASON_CHARS = 200;

    /** What a class name or a file path Claude names may not hold. */
    private static final Pattern NOT_NAME = Pattern.compile("[^A-Za-z0-9_$./-]");

    private static final int NAME_CHARS = 120;

    private final GithubClient github;

    private final PrTestRuns prTests;

    private final StandingVisas standing;

    private final SuiteChooser chooser;

    private final ObjectMapper mapper;

    private final Executor pool;

    private final LongSupplier clock;

    /** Each head commit attempted, by its sha. */
    private final ConcurrentMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    private final Set<String> quiet = Collections.synchronizedSet(Collections.newSetFromMap(
        new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > QUIET_MAX;
            }
        }));

    private final AtomicBoolean sweeping = new AtomicBoolean();

    private final AtomicInteger pushes = new AtomicInteger();

    private volatile long lastSweepAt;

    /** The users the last sweep acted for. */
    private volatile int users;

    /** Off in the dev profile, so a local run never acts on PRs next to the production instance. */
    @Value("${automation.enabled:true}")
    private boolean automation = true;

    @Autowired
    SuiteFixService(GithubClient github, PrTestRuns prTests, StandingVisas standing, SuiteChooser chooser,
        ObjectMapper mapper) {
        this(github, prTests, standing, chooser, mapper, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "suite-fix");
            t.setDaemon(true);

            return t;
        }), System::currentTimeMillis);
    }

    SuiteFixService(GithubClient github, PrTestRuns prTests, StandingVisas standing, SuiteChooser chooser,
        ObjectMapper mapper, Executor pool, LongSupplier clock) {
        this.github = github;
        this.prTests = prTests;
        this.standing = standing;
        this.chooser = chooser;
        this.mapper = mapper;
        this.pool = pool;
        this.clock = clock;
    }

    /** Whether the option can work here: the operator gave the Anthropic API key. */
    public boolean available() {
        return chooser.available();
    }

    /** What the status page shows: whether it works here, for how many users, how many commits, the last sweep. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available());
        out.put("users", users);
        out.put("attempts", attempts.size());
        out.put("pushed", pushes.get());
        out.put("lastSweepAt", lastSweepAt);

        return out;
    }

    /** The sweep, on a thread of its own: it waits for GitHub and Claude, and the scheduler has three threads. */
    @Scheduled(fixedDelay = SWEEP_MS, initialDelay = 3 * 60_000L)
    void sweepSoon() {
        if (!automation || !available() || !sweeping.compareAndSet(false, true))
            return;

        pool.execute(() -> {
            try {
                sweep();
            }
            finally {
                sweeping.set(false);
            }
        });
    }

    void sweep() {
        long started = clock.getAsLong();
        Map<String, StandingVisas.GhActor> fixers = standing.suiteFixers();
        users = fixers.size();
        fixers.forEach((login, actor) -> {
            List<Integer> prs;
            try {
                prs = github.openPrsBy(login);
            }
            catch (RuntimeException e) {
                log.warn("could not list the open PRs of {}: {}", login, e.toString());

                return;
            }
            for (int pr : prs) {
                try {
                    fixOnce(pr, login, actor);
                }
                catch (RuntimeException e) {
                    log.warn("adding the tests of PR {} to suites failed: {}", pr, e.toString());
                }
            }
        });
        followUp();
        attempts.values().removeIf(a -> a.at() < started - KEEP_MS);
        lastSweepAt = started;
    }

    /**
     * Adds the classes Ignite's check of the PR's head finds in no suite, when they are the PR's own and the head has
     * not been attempted: never another author's PR, nor a commit of this service's own.
     */
    void fixOnce(int pr, String login, StandingVisas.GhActor actor) {
        GithubClient.PrHead head = github.prHead(pr);
        String sha = head.headSha();
        if (sha == null || !login.equalsIgnoreCase(head.authorLogin()) || attempts.containsKey(sha)
            || quiet.contains(sha) || attempts.values().stream().anyMatch(a -> sha.equals(a.pushed())))
            return;

        AbandonedTestsCheck.Outcome check = prTests.suiteCheck(sha);
        if (!check.settled())
            return;

        Map<String, String> ours = new LinkedHashMap<>();
        if (check.state() == State.FAILED) {
            for (GithubClient.PrFile f : github.prTestFiles(pr)) {
                String fqcn = TestSuites.classOf(f.path());
                if (check.classes().contains(fqcn))
                    ours.put(fqcn, f.path());
            }
        }
        if (ours.isEmpty()) {
            quiet.add(sha);

            return;
        }

        Attempt started = new Attempt(pr, sha, clock.getAsLong(), actor.username(), null, null, null, List.of(),
            false);
        if (attempts.putIfAbsent(sha, started) != null)
            return;

        log.info("PR {}: Ignite's check of {} finds {} of its test classes in no suite, adding them", pr,
            sha.substring(0, Math.min(8, sha.length())), ours.size());
        attempts.put(sha, attempt(started, head, check, ours, actor));
    }

    private Attempt attempt(Attempt a, GithubClient.PrHead head, AbandonedTestsCheck.Outcome check,
        Map<String, String> ours, StandingVisas.GhActor actor) {
        Module module = new Module(head);
        List<Placement> placed = new ArrayList<>();
        for (Map.Entry<String, String> c : ours.entrySet()) {
            placed.add(placed.size() < MAX_CLASSES ? place(c.getKey(), c.getValue(), module)
                : Placement.failed(c.getKey(), "the checker adds at most " + MAX_CLASSES + " classes at a time"));
        }

        String pushed = null;
        String commitFailure = null;
        if (!module.edited.isEmpty()) {
            try {
                pushed = github.commitFiles(actor.ghToken(), head.headRepo(), head.headRef(), head.headSha(),
                    module.edited, commitMessage(check.sha(), placed));
                pushes.incrementAndGet();
                log.info("PR {}: {} added to suites in {}", a.pr(), placed.stream().filter(Placement::added).count(),
                    pushed);
            }
            catch (RuntimeException e) {
                commitFailure = why(e, actor);
                log.warn("PR {}: the commit adding tests to suites failed: {}", a.pr(), e.toString());
            }
        }

        String report = report(check, head.headRef(), placed, pushed, commitFailure);
        Long commentId = null;
        try {
            commentId = github.addPrComment(actor.ghToken(), a.pr(), report).id();
        }
        catch (RuntimeException e) {
            why(e, actor);
            log.warn("PR {}: the report on adding tests to suites was not posted: {}", a.pr(), e.toString());
        }

        List<String> added = pushed == null ? List.of()
            : placed.stream().filter(Placement::added).map(Placement::fqcn).toList();

        return new Attempt(a.pr(), a.head(), a.at(), a.user(), pushed, commentId, report, added, pushed == null);
    }

    /** One class into the suite Claude picks, in the edits of {@code module}; or why not. */
    private Placement place(String fqcn, String path, Module module) {
        Optional<String> root = TestSuites.testRoot(path);
        if (root.isEmpty())
            return Placement.failed(fqcn, "its file is not under src/test/java");

        List<TestSuites.Suite> suites;
        try {
            suites = module.suites(root.get());
        }
        catch (TooMany e) {
            return Placement.failed(fqcn, e.getMessage());
        }
        catch (RuntimeException e) {
            log.warn("could not read the suites under {}: {}", root.get(), e.toString());

            return Placement.failed(fqcn, "GitHub did not give the suites of its module");
        }
        if (suites.isEmpty())
            return Placement.failed(fqcn, "no suite of its module lists classes one a line");

        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        Optional<String> naming = module.naming(root.get(), simple);
        if (naming.isPresent()) {
            return Placement.failed(fqcn, "`" + naming.get() + "` names it already, yet Ignite's check finds it in no"
                + " suite: that suite may not run it");
        }

        Optional<String> source;
        try {
            source = github.rawFileUpTo(module.head.headRepo(), module.head.headSha(), path, MAX_FILE_BYTES);
        }
        catch (RuntimeException e) {
            return Placement.failed(fqcn, "GitHub did not give its file");
        }
        if (source.isEmpty())
            return Placement.failed(fqcn, "its file is too large to send");

        SuiteChooser.Choice choice;
        try {
            choice = chooser.choose(path, source.get(), suites);
        }
        catch (SuiteChooser.NoChoice e) {
            return Placement.failed(fqcn, e.getMessage());
        }

        Optional<TestSuites.Suite> suite = suites.stream().filter(s -> s.path().equals(choice.suite())).findFirst();
        if (suite.isEmpty())
            return Placement.failed(fqcn, "Claude named a suite its module does not have: " + named(choice.suite()));
        if (!suite.get().lists(choice.neighbour())) {
            return Placement.failed(fqcn, "Claude put it next to " + named(choice.neighbour()) + ", which `"
                + suite.get().name() + "` does not list");
        }

        TestSuites.Edit edit = TestSuites.add(module.current(suite.get().path()), choice.neighbour(), fqcn);
        if (edit.failure() != null)
            return Placement.failed(fqcn, "`" + suite.get().name() + "`: " + edit.failure());

        module.edited.put(suite.get().path(), edit.source());

        return new Placement(fqcn, suite.get().name(), choice.neighbour(), plain(choice.reason()), null);
    }

    /** Edits the comment of each pushed attempt with what Ignite's check says of the commit, once it says it. */
    void followUp() {
        long now = clock.getAsLong();
        for (Attempt a : List.copyOf(attempts.values())) {
            if (a.followed() || a.pushed() == null || a.commentId() == null || a.report() == null)
                continue;
            if (now - a.at() > FOLLOW_MS) {
                attempts.put(a.head(), a.followedWith(a.report()));
                continue;
            }

            AbandonedTestsCheck.Outcome check = prTests.suiteCheck(a.pushed());
            if (!check.settled())
                continue;

            String body = a.report().replace(pending(a.pushed()), verdict(check, a.pushed(), a.added()));
            Optional<StandingVisas.GhActor> actor = standing.actor(a.user()).filter(x -> x.ghToken() != null);
            try {
                if (actor.isPresent())
                    github.updatePrComment(actor.get().ghToken(), a.commentId(), body);
            }
            catch (RuntimeException e) {
                why(e, actor.get());
                log.warn("PR {}: the report on adding tests to suites was not updated: {}", a.pr(), e.toString());
            }
            attempts.put(a.head(), a.followedWith(body));
        }
    }

    /** What went wrong with a GitHub call made with the author's token; a refused token is dropped, as elsewhere. */
    private String why(RuntimeException e, StandingVisas.GhActor actor) {
        if (!(e instanceof RestClientResponseException rest))
            return "GitHub could not be reached";

        int code = rest.getStatusCode().value();
        if (code == 401) {
            standing.dropGhToken(actor.username());

            return "GitHub refused your token";
        }

        return code == 422 ? "the branch moved on meanwhile, or GitHub refused the commit (422)"
            : "GitHub answered " + code;
    }

    /** The PR comment: what Ignite's check found, what went where and why, and what was pushed. */
    static String report(AbandonedTestsCheck.Outcome check, String branch, List<Placement> placed, String pushed,
        String commitFailure) {
        int n = placed.size();
        long added = placed.stream().filter(Placement::added).count();
        String check7 = shortSha(check.sha());
        StringBuilder b = new StringBuilder("🧩 **Ignite PR Checker: test suites.** Ignite's ")
            .append(check.url() == null ? "abandoned-tests check" : "[abandoned-tests check](" + check.url() + ")")
            .append(" on `").append(check7).append("` found ").append(n)
            .append(n == 1 ? " test class" : " test classes").append(" of this PR in no test suite, so CI never runs ")
            .append(n == 1 ? "it." : "them.");
        if (pushed != null) {
            b.append(" `").append(shortSha(pushed)).append("` on your branch `").append(branch).append("` adds ")
                .append(added == n ? n == 1 ? "it" : "them" : added + " of them")
                .append(added == 1 ? " to a suite" : ", each to a suite")
                .append(" Claude picked (`git pull` before your next push):");
        }
        else if (commitFailure != null) {
            b.append(" Claude picked suites, but the commit failed: ").append(commitFailure)
                .append(". Nothing was pushed:");
        }
        else
            b.append(" Nothing was pushed:");
        b.append("\n");

        for (Placement p : placed) {
            String cls = "`" + p.fqcn().substring(p.fqcn().lastIndexOf('.') + 1) + "`";
            if (p.added()) {
                b.append("\n- ").append(cls).append(" → `").append(p.suite()).append("`, next to `")
                    .append(p.neighbour()).append('`').append(p.reason().isEmpty() ? "" : ": " + p.reason());
            }
            else
                b.append("\n- ").append(cls).append(": not added, ").append(p.failure()).append('.');
        }

        return b.append("\n\n").append(pushed != null ? pending(pushed)
            : "Add " + (n == 1 ? "it" : "them") + " to a test suite by hand, or mark " + (n == 1 ? "it" : "them")
                + " `@Ignore`.").toString();
    }

    /** The comment's last line while Ignite's check of the commit is not in. */
    static String pending(String pushed) {
        return "_Ignite's check runs again on `" + shortSha(pushed) + "`; its answer will be added here._";
    }

    /** What Ignite's check of the pushed commit says of the classes it added. */
    static String verdict(AbandonedTestsCheck.Outcome check, String pushed, List<String> added) {
        String on = " on `" + shortSha(pushed) + "`";

        return switch (check.state()) {
            case PASSED -> "✅ Ignite's check" + on + " finds every test class in a suite.";
            case FAILED -> {
                List<String> still = added.stream().filter(check.classes()::contains)
                    .map(c -> "`" + c.substring(c.lastIndexOf('.') + 1) + "`").toList();
                yield still.isEmpty() ? "✅ Ignite's check" + on + " finds the classes added here in a suite."
                    : "⚠️ Ignite's check" + on + " still finds " + String.join(", ", still) + " in no test suite:"
                        + " the suite picked may not run " + (still.size() == 1 ? "it" : "them")
                        + ". Move " + (still.size() == 1 ? "it" : "them") + " by hand.";
            }
            case SKIPPED -> "Ignite's check did not run" + on + ": " + check.reason() + ".";
            default -> "Ignite's check" + on + " could not be read: " + check.reason() + ".";
        };
    }

    /** The commit's message: what was added where, for the branch's history. */
    static String commitMessage(String checked, List<Placement> placed) {
        StringBuilder b = new StringBuilder("Add test classes to suites (Ignite PR Checker)\n\nIgnite's abandoned-tests"
            + " check on " + shortSha(checked) + " found them in no test suite; Claude picked the suites.\n");
        placed.stream().filter(Placement::added).forEach(p -> b.append("\n- ")
            .append(p.fqcn().substring(p.fqcn().lastIndexOf('.') + 1)).append(" -> ").append(p.suite()));

        return b.append('\n').toString();
    }

    /** Claude's text as one plain line: no markup, link, mention or command can come through. */
    static String plain(String text) {
        String flat = NOT_PLAIN.matcher(text == null ? "" : text).replaceAll(" ").replaceAll("\\s+", " ").strip();

        return flat.length() > REASON_CHARS ? flat.substring(0, REASON_CHARS - 1).strip() + "…" : flat;
    }

    /** A name or path Claude gave, as code: only the characters of one, and not too long. */
    static String named(String text) {
        String kept = NOT_NAME.matcher(text == null ? "" : text).replaceAll("");

        return "`" + (kept.length() > NAME_CHARS ? kept.substring(0, NAME_CHARS) + "…" : kept) + "`";
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    @Override
    public String fileName() {
        return "suite-fixes.json";
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<Attempt> all = attempts.values().stream()
            .sorted(Comparator.comparingLong(Attempt::at).thenComparing(Attempt::head)).toList();
        Snapshots.writeAtomic(mapper, file, new Snapshot(all, pushes.get()));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Snapshot s = mapper.readValue(file.toFile(), Snapshot.class);
        if (s.attempts() != null)
            s.attempts().forEach(a -> attempts.put(a.head(), a));
        pushes.set(s.pushed());
    }

    /** A class's way into a suite: the suite's name, the neighbour and Claude's reason; or why it did not go in. */
    record Placement(String fqcn, String suite, String neighbour, String reason, String failure) {
        static Placement failed(String fqcn, String why) {
            return new Placement(fqcn, null, null, null, why);
        }

        boolean added() {
            return failure == null;
        }
    }

    /**
     * One head commit attempted: the PR, the user it acted for, the commit pushed (null when none), the comment and
     * its text, the classes added, and whether the comment has the answer of Ignite's check of the commit, or no
     * longer waits for it. The field names are the file format.
     */
    record Attempt(int pr, String head, long at, String user, String pushed, Long commentId, String report,
        List<String> added, boolean followed) {
        Attempt {
            added = added == null ? List.of() : List.copyOf(added);
        }

        Attempt followedWith(String body) {
            return new Attempt(pr, head, at, user, pushed, commentId, body, added, true);
        }
    }

    /** What {@code suite-fixes.json} holds. */
    record Snapshot(List<Attempt> attempts, int pushed) {
    }

    /** A module's suites can't all be read. */
    private static final class TooMany extends RuntimeException {
        TooMany(String why) {
            super(why);
        }
    }

    /** The suites of the modules one attempt reads, at the PR's head, and the edits made to them. */
    private final class Module {
        private final GithubClient.PrHead head;

        private final Map<String, List<TestSuites.Suite>> suites = new HashMap<>();

        private final Map<String, String> sources = new HashMap<>();

        private final Map<String, String> edited = new LinkedHashMap<>();

        Module(GithubClient.PrHead head) {
            this.head = head;
        }

        /** The suites under test root {@code root}, read once. */
        List<TestSuites.Suite> suites(String root) {
            List<TestSuites.Suite> known = suites.get(root);
            if (known != null)
                return known;

            List<GithubClient.Tree.File> named = github.filesUnder(head.headRepo(), head.headSha(), root).files()
                .stream().filter(f -> TestSuites.maybeSuite(f.path())).toList();
            if (named.size() > MAX_SUITE_FILES)
                throw new TooMany("its module has more than " + MAX_SUITE_FILES + " files named as suites");

            List<TestSuites.Suite> found = new ArrayList<>();
            long total = 0;
            for (GithubClient.Tree.File f : named) {
                if (f.size() > MAX_FILE_BYTES || total + f.size() > MAX_SUITES_BYTES)
                    continue;

                Optional<String> text = github.rawFileUpTo(head.headRepo(), head.headSha(), f.path(), MAX_FILE_BYTES);
                if (text.isEmpty())
                    continue;

                total += text.get().getBytes(StandardCharsets.UTF_8).length;
                sources.put(f.path(), text.get());
                TestSuites.read(f.path(), text.get()).ifPresent(found::add);
            }
            suites.put(root, found);

            return found;
        }

        /** The suite under {@code root} whose file names class {@code simple} as {@code simple.class}, if one does. */
        Optional<String> naming(String root, String simple) {
            Pattern named = Pattern.compile("(?<![\\w$])" + Pattern.quote(simple) + "\\.class\\b");

            return sources.entrySet().stream().filter(e -> e.getKey().startsWith(root + "/"))
                .filter(e -> named.matcher(e.getValue()).find())
                .map(e -> e.getKey().substring(e.getKey().lastIndexOf('/') + 1).replace(".java", ""))
                .sorted().findFirst();
        }

        /** The suite's source with this attempt's edits so far. */
        String current(String path) {
            return edited.getOrDefault(path, sources.get(path));
        }
    }
}
