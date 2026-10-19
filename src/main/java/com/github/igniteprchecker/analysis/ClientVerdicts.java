package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * The verdicts the checker's page shows for the PRs of the Python thin client. A PR's verdict is that of the newest
 * finished run of the client's suite on the PR's branch that tested the PR's own commit: a run someone started on the
 * branch without a commit tested the client's master. When the run before the newest one tested the same commits of the
 * client and of Ignite, the verdict counts the failed tests of both, as the verdict comment of a {@code /run-all} does.
 * For example, an auto re-run tests the same commits as the run it re-runs. The verdict is judged as
 * {@link BlockerAnalyzer#analyzeSuiteRun} judges the run of a {@code /run-all}, and kept by the newest run's build like
 * a RunAll's verdict. Which run is the newest is asked of TeamCity by the warm cycle, and by a view at most every
 * {@value #LOOK_EVERY_MS} ms: opening the page again costs ci2 nothing. A view, the page's ↻ and the warm cycle that
 * want the verdict of the same run while it is computed wait for that one compute, and the warm cycle yields to a view
 * that waits for one. The verdicts are not written to disk: the client has a few open PRs, and the first warm cycle
 * after a start computes them again.
 */
@Component
public class ClientVerdicts {
    /** How often a view of a PR may ask TeamCity which run is the newest, at most. */
    static final long LOOK_EVERY_MS = 120_000;

    /** The shortest wait before a verdict that TeamCity errors left incomplete is computed again. */
    static final long RETRY_WAIT_MS = 120_000;

    /** How many PRs' newest runs are kept: the open ones, and those opened on the page lately. */
    static final int KEPT_PRS = 200;

    private final TcClient tc;

    private final BlockerAnalyzer analyzer;

    private final GithubClient github;

    private final PythonClientProperties props;

    private final LongSupplier clock;

    private final TtlCache<Long, AnalysisResult> results;

    /** The computes under way by the newest run's build. */
    private final ConcurrentMap<Long, CompletableFuture<AnalysisResult>> inFlight = new ConcurrentHashMap<>();

    /** How many views and ↻ wait for a compute now. */
    private final AtomicInteger usersWaiting = new AtomicInteger();

    /** The newest run of each PR as TeamCity last named it, the least recently asked first. */
    private final Map<Integer, Looked> newest = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Looked> eldest) {
            return size() > KEPT_PRS;
        }
    });

    @Autowired
    public ClientVerdicts(TcClient tc, BlockerAnalyzer analyzer, GithubClient github, PythonClientProperties props,
        AnalysisProperties analysis) {
        this(tc, analyzer, github, props, analysis, System::currentTimeMillis);
    }

    ClientVerdicts(TcClient tc, BlockerAnalyzer analyzer, GithubClient github, PythonClientProperties props,
        AnalysisProperties analysis, LongSupplier clock) {
        this.tc = tc;
        this.analyzer = analyzer;
        this.github = github;
        this.props = props;
        this.clock = clock;
        this.results = new TtlCache<>(Duration.ofMinutes(analysis.cacheTtlMinutes()).toMillis(), clock);
    }

    /** The client's repo. */
    public String repo() {
        return props.repo();
    }

    /** The client's open PRs, as GitHub lists them. */
    public List<PrSummary> openPrs() {
        return github.openPrs(props.repo());
    }

    /**
     * The verdict of PR {@code pr} for the page: the one kept for its newest run, else computed now. Empty when the PR
     * has no run that tested its own commit.
     */
    public Optional<Verdict> verdict(String token, int pr) {
        Looked looked = newest.get(pr);
        if (looked == null || clock.getAsLong() - looked.at() >= LOOK_EVERY_MS)
            looked = lookUp(token, pr);
        if (looked.run() == null)
            return Optional.empty();

        Optional<AnalysisResult> kept = results.peek(looked.run().newestId());
        if (kept.isPresent() && !retryDue(kept.get())) {
            results.touch(looked.run().newestId());

            return Optional.of(new Verdict(kept.get(), looked.run()));
        }

        return Optional.of(new Verdict(computeForUser(token, pr, looked.run()), looked.run()));
    }

    /** The verdict kept for the PR's newest run, without asking TeamCity; empty when none is kept. */
    public Optional<Verdict> kept(int pr) {
        Looked looked = newest.get(pr);
        if (looked == null || looked.run() == null)
            return Optional.empty();

        Run run = looked.run();

        return results.peek(run.newestId()).map(r -> new Verdict(r, run));
    }

    /** The verdict of the PR's newest run, asked of TeamCity and computed now: the page's ↻. */
    public Optional<Verdict> fresh(String token, int pr) {
        Run run = lookUp(token, pr).run();

        return run == null ? Optional.empty() : Optional.of(new Verdict(computeForUser(token, pr, run), run));
    }

    /** Whether a view or ↻ waits for a compute now: the warm cycle yields to it. */
    public boolean userWaiting() {
        return usersWaiting.get() > 0;
    }

    /**
     * Warms PR {@code pr} for the warm cycle: asks TeamCity for its newest run, and computes the verdict only when the
     * run is new or its verdict is incomplete and due for another try. Returns whether it computed.
     */
    public boolean warm(String token, int pr) {
        Run run = lookUp(token, pr).run();
        if (run == null)
            return false;

        Optional<AnalysisResult> kept = results.peek(run.newestId());
        if (kept.isPresent() && !retryDue(kept.get())) {
            results.touch(run.newestId());

            return false;
        }

        compute(token, pr, run);

        return true;
    }

    /**
     * What the PR list badges of the PR's newest run: its blockers and how it stands against the PR's head. The code
     * it compares is the client's commit, not Ignite's. Null when the PR has no verdict kept.
     */
    public Caveats.Glance glance(int pr) {
        Looked looked = newest.get(pr);
        if (looked == null || looked.run() == null)
            return null;

        return results.peek(looked.run().newestId()).map(r -> {
            Caveats.Glance g = Caveats.Glance.of(r);

            return new Caveats.Glance(g.blockers(), g.watch(), g.covered(), looked.run().clientSha());
        }).orElse(null);
    }

    /** The TeamCity user who started the PR's newest run, null when not known: the list's "my run". */
    public String starter(int pr) {
        Looked looked = newest.get(pr);

        return looked == null || looked.run() == null ? null : looked.run().starter();
    }

    /** The PR's head as the list of the client's open PRs names it; null when the PR is not listed. */
    public String head(int pr) {
        return openPrs().stream().filter(p -> p.number() == pr).map(PrSummary::headSha).filter(Objects::nonNull)
            .findFirst().orElse(null);
    }

    /** Sweeps out expired verdicts. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void evictExpired() {
        results.evictExpired();
    }

    private Looked lookUp(String token, int pr) {
        List<Run> runs = tc.finishedRuns(token, props.suite(), PythonClientRuns.branch(pr), TcModel.OVERRIDDEN_BRANCH)
            .stream().map(this::ofItsCommit).filter(Objects::nonNull).limit(2).toList();
        Run run = runs.isEmpty() ? null : runs.size() == 2 && sameCommits(runs.get(0), runs.get(1))
            ? runs.get(0).after(runs.get(1)) : runs.get(0);
        Looked looked = new Looked(run, clock.getAsLong());
        newest.put(pr, looked);

        return looked;
    }

    private static boolean sameCommits(Run a, Run b) {
        return a.clientSha() != null && a.igniteSha() != null && a.clientSha().equals(b.clientSha())
            && a.igniteSha().equals(b.igniteSha());
    }

    /**
     * The run, when it tested the client at one of its own commits; null when it did not. The suite's first step checks
     * the client out at OVERRIDDEN_BRANCH, master unless the run was queued with a commit. A run TeamCity names no
     * OVERRIDDEN_BRANCH of is taken by its commits.
     */
    private Run ofItsCommit(TcModel.BranchRun b) {
        List<TcModel.RootedRevision> revisions = b.revisions() == null || b.revisions().revision() == null ? List.of()
            : b.revisions().revision();
        List<TcModel.Property> params = b.resultingProperties() == null || b.resultingProperties().property() == null
            ? List.of() : b.resultingProperties().property();
        String checkout = params.stream().filter(p -> TcModel.OVERRIDDEN_BRANCH.equals(p.name()))
            .map(TcModel.Property::value).findFirst().orElse(null);
        if (checkout != null && revisions.stream().noneMatch(r -> checkout.equals(r.version())))
            return null;

        String client = checkout != null ? checkout : commitOf(revisions, props.clientVcsRoot());
        String name = b.buildType() == null || b.buildType().name() == null ? props.suite() : b.buildType().name();
        String starter = b.triggered() == null || b.triggered().user() == null ? null
            : b.triggered().user().username();

        return new Run(b.id(), 0, name, starter, client, commitOf(revisions, props.igniteVcsRoot()));
    }

    private static String commitOf(List<TcModel.RootedRevision> revisions, String vcsRoot) {
        return revisions.stream().filter(r -> r.vcsRootInstance() != null
                && vcsRoot.equals(r.vcsRootInstance().vcsRootId()))
            .map(TcModel.RootedRevision::version).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private AnalysisResult computeForUser(String token, int pr, Run run) {
        usersWaiting.incrementAndGet();
        try {
            return compute(token, pr, run);
        }
        finally {
            usersWaiting.decrementAndGet();
        }
    }

    /** Computes the verdict of the run, or waits for the compute of it under way. */
    private AnalysisResult compute(String token, int pr, Run run) {
        long key = run.newestId();
        CompletableFuture<AnalysisResult> mine = new CompletableFuture<>();
        CompletableFuture<AnalysisResult> underWay = inFlight.putIfAbsent(key, mine);
        if (underWay != null)
            return joined(underWay, key);

        try {
            String branch = PythonClientRuns.branch(pr);
            AnalysisResult r = run.rerunId() == 0 ? analyzer.analyzeSuiteRun(token, pr, branch, run.buildId())
                : analyzer.analyzeSuiteRun(token, pr, branch, run.buildId(), run.rerunId());
            results.put(key, r);
            inFlight.remove(key, mine);
            mine.complete(r);

            return r;
        }
        catch (RuntimeException | Error e) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * The verdict of a compute begun with another token. TeamCity's answer to that token, perhaps a 401, is not
     * rethrown as is: the caller would take it as TeamCity's answer to its own token.
     */
    private static AnalysisResult joined(CompletableFuture<AnalysisResult> underWay, long key) {
        try {
            return underWay.join();
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof RestClientResponseException answer)
                throw new IllegalStateException("shared compute of build " + key + " failed", answer);
            if (e.getCause() instanceof RuntimeException re)
                throw re;
            throw e;
        }
    }

    private boolean retryDue(AnalysisResult r) {
        return r.incompleteSince() > 0 && clock.getAsLong() - r.computedAt() >= RETRY_WAIT_MS;
    }

    /**
     * A run of the client's suite: its build, a later run of it on the same commits, {@code rerunId}, 0 when none, the
     * suite's name, who started the newest of them, null when TeamCity names nobody, and the commits they tested of the
     * client and of Ignite, null when TeamCity did not name them.
     */
    public record Run(long buildId, long rerunId, String suiteName, String starter, String clientSha,
        String igniteSha) {
        /** The build of the newest run: the verdict is kept by it. */
        public long newestId() {
            return rerunId == 0 ? buildId : rerunId;
        }

        /** This run as the re-run of {@code earlier}, a run of the same commits. */
        Run after(Run earlier) {
            return new Run(earlier.buildId(), buildId, suiteName, starter, clientSha, igniteSha);
        }
    }

    /** A PR's verdict and the run it is of. */
    public record Verdict(AnalysisResult result, Run run) {
    }

    /** The newest run of a PR that tested its own commit, null when it has none, as TeamCity named it {@code at}. */
    private record Looked(Run run, long at) {
    }
}
