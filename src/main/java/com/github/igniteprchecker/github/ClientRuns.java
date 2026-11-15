package com.github.igniteprchecker.github;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ChainCollector;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.ClientVerdict;
import com.github.igniteprchecker.jira.ClientVisa;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.QueuedBelowTopException;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Runs of a thin client's suite for PRs of its repository. A {@code /run-all} in such a PR queues that one suite,
 * with the client at the PR's head and Ignite at the head of master as of the command. Both commits are named in the
 * request, so the run tests exactly them however long it waits in the queue, and an auto re-run of it tests them
 * again. Its verdict is judged by the rules of a suite of a RunAll, and told in the PR and in the visa. The checker's
 * page of such a PR has an address of its own, {@link #pageUrl}: the PR numbers of the client overlap Ignite's.
 */
@Component
public class ClientRuns {
    /** The shortest wait before a verdict that TeamCity errors left incomplete is computed again. */
    private static final long RETRY_WAIT_MS = 120_000;

    /**
     * How long a verdict with unchecked tests is held back and computed again: past that it is told as it is, with
     * its unchecked tests listed apart, as a RunAll's verdict is.
     */
    private static final long HOLD_INCOMPLETE_MS = 1_200_000;

    private final GithubClient github;

    private final TcClient tc;

    private final BlockerAnalyzer analyzer;

    /** Ignite's main branch as a git ref, such as {@code refs/heads/master}. */
    private final String baseBranch;

    private final String tcBase;

    /** Runs whose verdict TeamCity errors left incomplete: since when, and when it was computed last. */
    private final ConcurrentMap<Long, Tries> incomplete = new ConcurrentHashMap<>();

    public ClientRuns(GithubClient github, TcClient tc, BlockerAnalyzer analyzer,
        AnalysisProperties analysis, TeamcityProperties teamcity) {
        this.github = github;
        this.tc = tc;
        this.analyzer = analyzer;
        this.baseBranch = analysis.baseBranch();
        String base = teamcity.baseUrl() == null ? "https://ci2.ignite.apache.org/" : teamcity.baseUrl();
        this.tcBase = base.endsWith("/") ? base : base + "/";
    }

    public static String pageUrl(String publicUrl, ClientSpec spec, int pr) {
        return publicUrl + "/" + spec.key() + "?pr=" + pr;
    }

    /**
     * Queues the suite under {@code token} for PR {@code pr}: the client at the PR's head, Ignite at master's head,
     * both as GitHub has them now. {@code comment} tells on TeamCity who asked for it. A run TeamCity queued but did
     * not move to the top comes back with the status it refused that with. Throws {@link NotQueued} when the run
     * cannot be put together, and TeamCity's or GitHub's own error when they failed.
     */
    public Queued queue(String token, ClientSpec spec, int pr, boolean top, String comment) {
        return queuePrepared(token, prepare(token, spec, pr), top, comment);
    }

    /** Resolves both commits and verifies the VCS roots before any replacement is cancelled. */
    public PinnedRun prepare(String token, ClientSpec spec, int pr) {
        String clientSha = github.prHeadSha(spec.repo(), pr);
        if (clientSha == null || !clientSha.matches("[0-9a-f]{40}"))
            throw new NotQueued("GitHub returned no head commit for this PR");

        String master = baseBranch.replaceFirst("^refs/heads/", "");
        String igniteSha = github.branchHeadSha(github.repo(), master);
        if (igniteSha == null || !igniteSha.matches("[0-9a-f]{40}"))
            throw new NotQueued("GitHub returned no head commit of " + master + " in " + github.repo());

        Map<String, String> roots = tc.vcsRootInstances(token, spec.suite())
            .orElseThrow(() -> new NotQueued("TeamCity has no suite " + spec.suite()));
        List<String> missing = Stream.of(spec.igniteVcsRoot(), spec.clientVcsRoot())
            .filter(root -> !roots.containsKey(root)).toList();
        if (!missing.isEmpty()) {
            throw new NotQueued("suite " + spec.suite() + " has no VCS root " + String.join(" or ", missing)
                + " in TeamCity");
        }

        return new PinnedRun(spec.suite(), spec.branch(pr), List.of(
            PinnedRun.Pin.forRoot(spec.igniteVcsRoot(), baseBranch, igniteSha),
            PinnedRun.Pin.forRoot(spec.clientVcsRoot(), "refs/pull/" + pr + "/head", clientSha)),
            Map.of(TcModel.OVERRIDDEN_BRANCH, clientSha));
    }

    public Queued queuePrepared(String token, PinnedRun run, boolean top, String comment) {
        try {
            return new Queued(tc.queuePinned(token, run, top, comment), run, 0);
        }
        catch (QueuedBelowTopException e) {
            return new Queued(e.build(), run, e.status());
        }
    }

    /**
     * Cancels the user's own queued and running runs of the suite for the PR: a new {@code /run-all} supersedes
     * them. Returns how many.
     */
    public int cancelOwn(String token, String username, ClientSpec spec, int pr) {
        return tc.cancelOwnBuilds(token, spec.branch(pr), username, b -> spec.suite().equals(b.buildTypeId()));
    }

    /**
     * The verdict of a finished run of PR {@code pr}; empty while one that TeamCity errors left incomplete waits to be
     * computed again. Such a verdict is computed again at most every 2 minutes and is told as it is after 20.
     */
    public Optional<AnalysisResult> verdict(String token, ClientSpec spec, int pr, long buildId) {
        return verdict(token, spec, pr, buildId, 0);
    }

    /**
     * See {@link #verdict(String, ClientSpec, int, long)}: the verdict once the run's auto re-run {@code rerunId} has finished,
     * which counts the failed tests of both runs; {@code rerunId} 0 for the run alone.
     */
    public Optional<AnalysisResult> verdict(String token, ClientSpec spec, int pr, long buildId, long rerunId) {
        long now = System.currentTimeMillis();
        Tries tried = incomplete.get(buildId);
        if (tried != null && now - tried.lastAt() < RETRY_WAIT_MS)
            return Optional.empty();

        AnalysisResult r = rerunId == 0 ? analyzer.analyzeSuiteRun(token, pr, spec.branch(pr), buildId)
            : analyzer.analyzeSuiteRun(token, pr, spec.branch(pr), buildId, rerunId);
        if (r.incompleteSince() == 0 || tried != null && now - tried.since() >= HOLD_INCOMPLETE_MS) {
            incomplete.remove(buildId);

            return Optional.of(r);
        }

        incomplete.put(buildId, new Tries(tried == null ? now : tried.since(), now));

        return Optional.empty();
    }

    /**
     * Queues the run {@code buildId} of PR {@code pr} again under {@code token}, at the top of the queue: an auto
     * re-run, with the same commits and {@code OVERRIDDEN_BRANCH} it was {@code pinned} to, so it tests the same code
     * whatever Ignite master and the PR's head are now. A re-run TeamCity queued but did not move to the top comes back
     * with the status it refused that with.
     */
    public Queued rerun(String token, ClientSpec spec, int pr, long buildId, PinnedRun pinned) {
        PinnedRun stable = new PinnedRun(pinned.suite(), pinned.branch(), pinned.revisions().stream()
            .map(pin -> pin.vcsRootId() != null ? pin : PinnedRun.Pin.forRoot(
                pin.vcsBranch().startsWith("refs/pull/") ? spec.clientVcsRoot() : spec.igniteVcsRoot(),
                pin.vcsBranch(), pin.version())).toList(), pinned.properties());
        String comment = "Ignite PR Checker: auto re-run of build " + buildId + " for https://github.com/"
            + spec.repo() + "/pull/" + pr + ", on the same commits";
        try {
            return new Queued(tc.queuePinned(token, stable, true, comment), stable, 0);
        }
        catch (QueuedBelowTopException e) {
            return new Queued(e.build(), stable, e.status());
        }
    }

    /**
     * Whether the verdict blames something a re-run of the same commits can settle: a blocker, a test that started
     * failing, or a suite that broke for any reason but a compilation error, which the same code repeats. As for a
     * RunAll, a re-run that passes clears a failure, and one that fails again makes it a blocker.
     */
    public static boolean worthRerunning(AnalysisResult r) {
        return !r.blockers().isEmpty() || !r.watch().isEmpty()
            || r.brokenSuites().stream().anyMatch(s -> !s.compileError());
    }

    /**
     * Whether the run TeamCity reports {@code cancelled} is worth an auto re-run on the same commits: TeamCity cancelled
     * it by itself, not a person, and no run it needed failed. A RunAll's wave re-runs such a suite too.
     */
    public static boolean worthRerunning(TcModel.Build cancelled) {
        return ChainCollector.cancelledByTeamCity(cancelled);
    }

    /** Whether PR {@code pr} of the client is open, and its title; empty when GitHub has no such PR. */
    public Optional<GithubClient.PullState> pullState(ClientSpec spec, int pr) {
        return github.pullState(spec.repo(), pr);
    }

    /**
     * The verdict comment of the run {@code buildId} of the suite {@code suiteName}, which mentions {@code login} when
     * known. {@code pinned}: what the run was queued with. It says what became of the run's auto {@code rerun}, null
     * when none was due, and of its {@code visa}, and links the checker's {@code page} of the PR. {@code fromPage}: the
     * run was queued on that page, not by a {@code /run-all}.
     */
    public String verdictComment(ClientSpec spec, String login, int pr, long buildId, String suiteName, PinnedRun pinned,
        AnalysisResult r, ClientVerdict.Rerun rerun, ClientVisa visa, String page, boolean fromPage) {
        return ClientVerdict.markdown(tcBase, login, run(spec, pr, buildId, suiteName, pinned, page), r, rerun, visa,
            fromPage);
    }

    /** The visa of the run: its verdict in JIRA's wiki markup, which says that it is of one suite. */
    public String visaBody(ClientSpec spec, int pr, long buildId, String suiteName, PinnedRun pinned, AnalysisResult r,
        ClientVerdict.Rerun rerun) {
        return ClientVerdict.wiki(tcBase, run(spec, pr, buildId, suiteName, pinned, null), r, rerun);
    }

    /**
     * The visa of the run {@code buildId} of the suite {@code suiteName} for Post to JIRA on the checker's page of PR
     * {@code pr}, as the run's automatic visa says it: it names the commits of the client and of Ignite the run tested,
     * {@code clientSha} and {@code igniteSha}, and {@code rerunId}, the later run of the same commits that the verdict
     * {@code r} counts too, 0 when none.
     */
    public String pageVisaBody(ClientSpec spec, int pr, long buildId, long rerunId, String suiteName, String clientSha,
        String igniteSha, AnalysisResult r) {
        return ClientVerdict.wiki(tcBase, new ClientVerdict.Run(buildId, suiteName, github.repo(), igniteSha,
            spec.repo(), pr, clientSha), r, rerunId == 0 ? null : ClientVerdict.Rerun.sameCommits(rerunId));
    }

    /**
     * The verdict comment of the run {@code buildId} of the suite {@code suiteName} for Post to PR on the checker's
     * {@code page} of PR {@code pr}, as the run's verdict comment says it: it mentions {@code login} when known, and
     * names the commits of the client and of Ignite the run tested, {@code clientSha} and {@code igniteSha}, and
     * {@code rerunId}, the later run of the same commits that the verdict {@code r} counts too, 0 when none. {@code
     * fromPage}: how the run was queued, null when the checker does not know.
     */
    public String pageCommentBody(ClientSpec spec, String login, int pr, long buildId, long rerunId, String suiteName,
        String clientSha, String igniteSha, AnalysisResult r, Boolean fromPage, String page) {
        return ClientVerdict.pageMarkdown(tcBase, login, new ClientVerdict.Run(buildId, suiteName, github.repo(),
            igniteSha, spec.repo(), pr, clientSha, page), r,
            rerunId == 0 ? null : ClientVerdict.Rerun.sameCommits(rerunId), fromPage);
    }

    /** The suite's name as TeamCity gives it with the build; its id when it gives none. */
    public static String suiteName(TcModel.Build build, PinnedRun pinned) {
        return build.buildType() != null && build.buildType().name() != null ? build.buildType().name()
            : pinned.suite();
    }

    private ClientVerdict.Run run(ClientSpec spec, int pr, long buildId, String suiteName, PinnedRun pinned, String page) {
        String clientSha = pinned.properties().get(TcModel.OVERRIDDEN_BRANCH);
        String igniteSha = pinned.revisions().stream().map(PinnedRun.Pin::version)
            .filter(v -> !v.equals(clientSha)).findFirst().orElse(null);

        return new ClientVerdict.Run(buildId, suiteName, github.repo(), igniteSha, spec.repo(), pr, clientSha, page);
    }

    /**
     * A run TeamCity queued: the build, what it is pinned to, and the HTTP status TeamCity refused to move it to the
     * top with; 0 when it did not refuse.
     */
    public record Queued(TcModel.Build build, PinnedRun pinned, int belowTop) {
    }

    /** The run could not be put together; the message says why, in words for the commander. */
    public static final class NotQueued extends RuntimeException {
        public NotQueued(String why) {
            super(why);
        }
    }

    /** A verdict TeamCity errors left incomplete: since when, and when it was computed last. */
    private record Tries(long since, long lastAt) {
    }
}
