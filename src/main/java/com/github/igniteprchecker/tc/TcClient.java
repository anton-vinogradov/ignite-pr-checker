package com.github.igniteprchecker.tc;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin wrapper over the TeamCity REST API. Stateless with respect to auth: every call takes the
 * caller's personal token, so requests run with that user's TeamCity permissions.
 *
 * <p>Query values are percent-encoded by hand as a precaution: the TeamCity WAF rejects some raw
 * characters (e.g. {@code [}/{@code ]}) in the URL. Locators here use numeric ids to avoid them.
 */
@Component
public class TcClient {
    private static final Logger log = LoggerFactory.getLogger(TcClient.class);

    /**
     * Longer than any build on a PR branch runs: suites are cut off by their execution timeouts, and
     * a chain finishes together with its last suite. So a build that finished after some moment
     * started at most this long before it.
     */
    private static final long LONGEST_BUILD_SECONDS = 86_400;

    /** How many of a test's latest runs on other branches its comparison with other PRs reads. */
    private static final int OTHER_BRANCH_RUNS = 200;

    /**
     * How many of a PR's newest RunAll chains the one lookup of the chain to analyse reads: a chain to show
     * is nearly always among them, and a fuller list costs nothing more.
     */
    private static final int LATEST_CHAINS = 5;

    /** The most builds {@link #suitesFinishedAfter} reads; a branch that finished more is taken as unknown. */
    private static final int FINISHED_SINCE_MAX = 1000;

    /** Where {@link #occurrencesWithConditions} puts the run conditions into a fields spec. */
    private static final String CONDITIONS_SLOT = "{conditions}";

    /**
     * The build parameters a test run is compared by: the JDK and the scale factor Ignite tests shrink
     * their workloads by. Master's nightly RunAll runs with 1.0, PR chains with 0.1.
     */
    private static final String RUN_CONDITIONS = "resultingProperties($locator(name:(value:("
        + TcModel.JAVA_HOME + "|" + TcModel.TEST_SCALE_FACTOR + "),matchType:matches)),property(name,value))";

    /** The JDK alone, in the form ci2 is known to answer. */
    private static final String JDK_ONLY = "resultingProperties($locator(name:" + TcModel.JAVA_HOME
        + "),property(name,value))";

    private final RestClient http;

    private final String baseUrl;

    private final AnalysisProperties analysis;

    private final Metrics metrics;

    /**
     * The run conditions asked for with each test run: the JDK and the test scale factor its build ran
     * with. Starts as both, and drops to the JDK alone if TeamCity rejects the pattern that names both.
     */
    private volatile String runConditions = RUN_CONDITIONS;

    /**
     * Whether the chain to analyse is found with one lookup of the newest chains. Drops to the three
     * lookups it replaced if TeamCity rejects that lookup.
     */
    private volatile boolean oneChainLookup = true;

    /** Whether {@link #suitesFinishedAfter} still asks for failed-to-start builds; dropped if TeamCity rejects it. */
    private volatile boolean finishedSinceFailedToStart = true;

    public TcClient(TeamcityProperties tc, AnalysisProperties analysis, Metrics metrics) {
        this.analysis = analysis;
        this.metrics = metrics;
        this.baseUrl = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        this.http = RestClient.builder()
            .requestFactory(OutboundHttp.plain(tc.readTimeout()))
            .defaultHeader("Accept", "application/json")
            .build();
    }

    /**
     * The TeamCity username the token belongs to, or empty if TeamCity rejects the token (401). Any other
     * error answer is thrown: it says nothing about the token (the ci2 WAF answers 403 to valid requests too).
     */
    public Optional<String> currentUsername(String token) {
        try {
            TcModel.User user = get("whoami", token, url("app/rest/users/current", query("fields", "username")),
                TcModel.User.class);

            return user == null ? Optional.empty() : Optional.ofNullable(user.username());
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401)
                return Optional.empty();

            throw e;
        }
    }

    /**
     * Whether TeamCity itself refuses this token (401). A firewall 403 or an outage says nothing about
     * the token, so neither counts.
     */
    public boolean tokenRejected(String token) {
        try {
            get("whoami", token, url("app/rest/users/current", query("fields", "username")), TcModel.User.class);

            return false;
        }
        catch (RestClientResponseException e) {
            return e.getStatusCode().value() == 401;
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Latest <em>finished, non-cancelled</em> RunAll chain build for a pull request branch, if any.
     * Cancelled runs (status {@code UNKNOWN}) and still-running/queued builds are skipped — the strict
     * baseline used to decide whether to auto-post a visa (never for a run someone cancelled).
     */
    public Optional<TcModel.Build> findRunAllBuildForPr(String token, int prNumber) {
        return findRunAllBuild(token, prNumber, true);
    }

    /**
     * The RunAll build to <em>show</em> for a PR: the clean run if there is one, else — only then —
     * the latest finished <em>cancelled</em> run, so a chain that got through most of its suites
     * before being cancelled still shows its real failures (analysed as partial/interrupted) instead
     * of "no run at all"; and failing both, the run that is still going. Strict-first order keeps a
     * fresh cancel (or a fresh start) from shadowing a good verdict.
     *
     * <p>The last step matters most on a PR's first RunAll: suites fail hours before the chain ends,
     * and until it did, the page said "no run at all" while a dozen of them were already red. Nothing
     * that acts on a verdict uses this — the visa and auto re-run take the strict, finished-only
     * {@link #findRunAllBuildForPr}.
     *
     * <p>One lookup of the PR's newest chains answers all three questions. Asked one at a time, they cost up to
     * three calls per PR, for each of the 50 PRs the warmer checks every 10 minutes.
     */
    public Optional<TcModel.Build> findRunAllBuildForAnalysis(String token, int prNumber) {
        if (!oneChainLookup)
            return findRunAllBuildOneStateAtATime(token, prNumber);

        List<TcModel.Build> latest;
        try {
            latest = latestChains(token, prNumber);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400)
                throw e;

            oneChainLookup = false;
            log.warn("TeamCity rejected the lookup of a PR's newest chains ({}); finding the chain to analyse "
                + "with three lookups, as before", e.getStatusText());

            return findRunAllBuildOneStateAtATime(token, prNumber);
        }

        // Past a full list, an older chain may still be the one: asked for as before, which is rare.
        boolean more = latest.size() >= LATEST_CHAINS;
        Optional<TcModel.Build> clean = first(latest, b -> finished(b) && b.canceledInfo() == null);
        if (clean.isEmpty() && more)
            clean = findRunAllBuild(token, prNumber, true);
        if (clean.isPresent())
            return clean;

        Optional<TcModel.Build> cancelled = first(latest, TcClient::finished);
        if (cancelled.isEmpty() && more)
            cancelled = findRunAllBuild(token, prNumber, false);
        if (cancelled.isPresent())
            return cancelled;

        Optional<TcModel.Build> running = first(latest, b -> "running".equalsIgnoreCase(b.state()));

        return running.isEmpty() && more ? findRunningRunAll(token, prNumber) : running;
    }

    /**
     * The PR branch's newest RunAll chains in one call, newest first, whatever their state, with what tells a
     * cancelled one: TeamCity's {@code canceled} dimension is whether a build has cancellation info. The
     * locator is that of {@link #recentChains}, which ci2 answers, with personal builds left out as by
     * default.
     */
    private List<TcModel.Build> latestChains(String token, int prNumber) {
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),defaultFilter:false,personal:false,count:" + LATEST_CHAINS;

        TcModel.BuildList list = get("findBuild", token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,status,state,branchName,finishDate,triggered(type,user(username)),"
                + "canceledInfo(text,user(username)))")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /** The chain to analyse as three lookups found it: the clean one, else the cancelled one, else the running one. */
    private Optional<TcModel.Build> findRunAllBuildOneStateAtATime(String token, int prNumber) {
        Optional<TcModel.Build> clean = findRunAllBuild(token, prNumber, true);
        if (clean.isPresent())
            return clean;

        Optional<TcModel.Build> cancelled = findRunAllBuild(token, prNumber, false);

        return cancelled.isPresent() ? cancelled : findRunningRunAll(token, prNumber);
    }

    private static boolean finished(TcModel.Build b) {
        return "finished".equalsIgnoreCase(b.state());
    }

    private static Optional<TcModel.Build> first(List<TcModel.Build> builds, Predicate<TcModel.Build> matching) {
        return builds.stream().filter(matching).findFirst();
    }

    /** The chain still running for this PR, newest first; empty when nothing is under way. */
    private Optional<TcModel.Build> findRunningRunAll(String token, int prNumber) {
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),state:running,count:1";

        TcModel.BuildList list = get("findBuild", token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,status,state,statusText,branchName,triggered(type,user(username)))")), TcModel.BuildList.class);

        return list == null || list.build() == null || list.build().isEmpty()
            ? Optional.empty() : Optional.of(list.build().get(0));
    }

    private Optional<TcModel.Build> findRunAllBuild(String token, int prNumber, boolean nonCancelledOnly) {
        // TeamCity's default build filter already excludes cancelled runs, so the fallback must ask
        // for them explicitly with canceled:any (dropping canceled:false is not enough).
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),state:finished,failedToStart:any,count:1"
            + (nonCancelledOnly ? ",canceled:false" : ",canceled:any");

        TcModel.BuildList list = get("findBuild", token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,status,state,statusText,branchName,finishDate,triggered(type,user(username)))")), TcModel.BuildList.class);

        if (list == null || list.build() == null || list.build().isEmpty())
            return Optional.empty();

        return Optional.of(list.build().get(0));
    }

    /**
      * The PR branch's most recent RunAll chains of ANY state (newest first): running, finished,
      * cancelled or interrupted. Used to fold in results from a run that didn't fully complete — a
      * cancelled or interrupted chain still ran (and failed) some of its suites.
      */
    public List<TcModel.Build> recentChains(String token, int prNumber, int count) {
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),defaultFilter:false,count:" + count;

        TcModel.BuildList list = get("recentChains", token, url("app/rest/builds", query(
            "locator", locator, "fields", "build(id,state)")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /**
     * The PR branch's finished FAILURE builds of any kind that started since the given TeamCity time,
     * or the newest ones when it is null. The single-suite re-runs among them belong to no chain, so
     * no dependency walk ever sees them; callers pick them out. Callers bound it by a chain's queue
     * time rather than by {@code sinceBuild}: on ci2 that cut RunAll 9389046 at 22:05, 43 minutes after
     * the chain started, and so dropped the early re-runs of the suites that had failed by then. Every
     * build queued after the chain starts after its queue time, so this bound drops none.
     */
    public List<TcModel.Build> failedBuildsSince(String token, int prNumber, String since) {
        String locator = "branch:(name:pull/" + prNumber + "/head),state:finished,status:FAILURE"
            + (since == null ? "" : ",sinceDate:" + since) + ",count:100";

        TcModel.BuildList list = get("branchFailures", token, url("app/rest/builds", query(
            "locator", locator, "fields", "build(" + SUITE_FIELDS + ")")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /**
     * The PR branch's finished, not cancelled builds of any kind that started since the given TeamCity time,
     * or the newest ones when it is null, with their test counts: what may have run a suite a chain left
     * unrun since, and whether it ran in full. One call for all its suites; a chain and its re-runs fit well
     * within the count.
     */
    public List<TcModel.Build> finishedBuildsSince(String token, int prNumber, String since) {
        String locator = "branch:(name:pull/" + prNumber + "/head),state:finished,canceled:false"
            + (since == null ? "" : ",sinceDate:" + since) + ",count:1000";

        TcModel.BuildList list = get("branchRuns", token, url("app/rest/builds", query(
            "locator", locator, "fields", "build(id,buildTypeId,status,testOccurrences(count))")),
            TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /**
     * Every RunAll chain running right now, across all branches, in one call — with who started it
     * and what {@link RerunTracker#record} needs to watch it. {@code branch:(default:any)} is spelled
     * out so the answer never depends on TeamCity's default-branch filter.
     */
    public List<TcModel.Build> runningRunAllChains(String token) {
        String locator = "buildType:(id:" + analysis.runAllBuildType() + "),branch:(default:any),state:running,count:100";

        TcModel.BuildList list = get("runningChains", token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,state,branchName,buildTypeId,webUrl,buildType(name),triggered(type,user(username)))")),
            TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /**
     * What a suite run is judged by. A chain's dependency and a re-run outside any chain are fetched
     * with the same fields, so the same suite rules see the same facts about both.
     */
    private static final String SUITE_FIELDS = "id,buildTypeId,status,state,queuedDate,buildType(name),"
        + "testOccurrences(count),problemOccurrences(problemOccurrence(type,details)),canceledInfo(text,user(username))";

    /** A build with its snapshot-dependency builds expanded (the individual suites of a chain). */
    public TcModel.Build getBuildWithDeps(String token, long buildId) {
        return get("deps", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,status,state,branchName,queuedDate,startDate,finishDate,buildType(id,name),"
                + "revisions(revision(version)),snapshot-dependencies(build(" + SUITE_FIELDS + "))")),
            TcModel.Build.class);
    }

    /**
     * How many tests each suite runs on the base branch right now — the honest baseline for spotting a
     * suite that silently ran far fewer tests in a PR. Two calls for all ~150 suites: the newest
     * finished master chain, then its dependencies' test counts.
     */
    public MasterSuiteStats masterSuiteStats(String token) {
        TcModel.BuildList chains = get("baseline", token, url("app/rest/builds", query(
            "locator", "buildType:(id:" + analysis.runAllBuildType() + "),branch:(default:true),"
                + "state:finished,canceled:false,count:1",
            "fields", "build(id)")), TcModel.BuildList.class);

        if (chains == null || chains.build() == null || chains.build().isEmpty())
            return new MasterSuiteStats(Map.of(), Map.of(), 0);

        TcModel.Build chain = get("baseline", token,
            url("app/rest/builds/id:" + chains.build().get(0).id(), query(
                "fields", "snapshot-dependencies(build(buildTypeId,testOccurrences(count),startDate,finishDate))")),
            TcModel.Build.class);

        if (chain == null || chain.snapshotDependencies() == null || chain.snapshotDependencies().build() == null)
            return new MasterSuiteStats(Map.of(), Map.of(), 0);

        Map<String, Integer> counts = new java.util.HashMap<>();
        Map<String, Long> durations = new java.util.HashMap<>();
        java.util.Set<String> suites = new java.util.HashSet<>();
        for (TcModel.Build dep : chain.snapshotDependencies().build()) {
            if (dep.buildTypeId() == null)
                continue;
            suites.add(dep.buildTypeId());
            if (dep.testOccurrences() != null)
                counts.put(dep.buildTypeId(), dep.testOccurrences().count());
            long start = TcDates.epochSeconds(dep.startDate());
            long finish = TcDates.epochSeconds(dep.finishDate());
            if (start > 0 && finish > start)
                durations.put(dep.buildTypeId(), finish - start);
        }

        return new MasterSuiteStats(counts, durations, suites.size());
    }

    /**
     * Per-suite master facts: how many tests it runs, and how long it typically takes (seconds); and how
     * many suites the chain has, those that run no tests included.
     */
    public record MasterSuiteStats(Map<String, Integer> counts, Map<String, Long> durations, int suites) {
    }

    /**
     * Failed test occurrences of a single build, muted ones left out. TeamCity does not fail a build on a
     * muted test and someone muted it on purpose, so it is never a PR's blocker; skipping it here, in
     * every suite, keeps a build's list equal to the failed count TeamCity shows for that build. Counting
     * it only when its suite failed for another reason made the same muted test a candidate in one suite
     * and invisible in the next.
     */
    public List<TcModel.TestOccurrence> getFailedTests(String token, long buildId) {
        TcModel.TestOccurrences occ = get("failedTests", token, url("app/rest/testOccurrences", query(
            "locator", "build:(id:" + buildId + "),status:FAILURE,muted:false,count:2000",
            "fields", "testOccurrence(id,name,status,test(id))")), TcModel.TestOccurrences.class);

        return occ == null || occ.testOccurrence() == null ? List.of() : occ.testOccurrence();
    }

    /**
     * Recent master history of one test in one suite (up to {@code analysis.historyDepth} runs), newest
     * first: the statuses and the conditions each build ran under. Per suite, because one test id runs
     * in several suites of a chain (the C++ tests run on Windows, Linux and Clang) and each has its own
     * failure rate: mixed together, a platform that flakes on master would make a clean break on another
     * platform look pre-existing. The conditions come in the same request: some nightly master RunAlls
     * run on JDK 21, and a test broken only on JDK 21 is no evidence about a JDK 17 run.
     */
    public List<TcModel.TestOccurrence> getBaseBranchHistory(String token, long testId, String buildTypeId) {
        TcModel.TestOccurrences occ = occurrencesWithConditions("history", token,
            "test:(id:" + testId + "),branch:(default:true),buildType:(id:" + buildTypeId + "),count:"
                + analysis.historyDepth(),
            "testOccurrence(status,build(id," + CONDITIONS_SLOT + "))");

        if (occ == null || occ.testOccurrence() == null)
            return List.of();

        return occ.testOccurrence().stream()
            .sorted(Comparator.comparingLong((TcModel.TestOccurrence o) -> o.build() == null ? 0 : o.build().id())
                .reversed())
            .toList();
    }

    /**
     * The test's <em>finished, non-cancelled</em> runs in one suite on the PR branch, oldest → newest
     * (up to 100 — effectively every run of any real PR). The last element is the latest completed run
     * (a blocker must still be FAILURE there — a passing re-run clears it); the whole sequence backs the
     * per-blocker pass/fail history strip. One request.
     *
     * <p>Only that suite's runs: the same test id in a sibling suite of the chain is another platform's
     * run, not a re-run, and taken for one, its pass would clear a real failure with a lower build id.
     *
     * <p>Each run carries the revision its build ran on: a pass only says something about the code
     * under review if it happened on the <em>same</em> revision as the failure. Asked for in this same
     * request, so classification can tell "passed on the same code" from "passed on older code". So do
     * the conditions the build ran under, which pick the master runs the PR's failure is compared with.
     *
     * <p>Muted failures are left out, as in {@link #getFailedTests}: a muted failure is no evidence the PR
     * broke the test, and no pass either. Kept in, it would lengthen a blocker's fail streak and make
     * an occurrence TeamCity ignores the "last finished run" the verdict is anchored to. The test's
     * passes stay in: TeamCity records them as not muted even while the test is muted.
     */
    public List<TcModel.TestOccurrence> prBranchRuns(String token, int prNumber, long testId, String buildTypeId) {
        TcModel.TestOccurrences occ = occurrencesWithConditions("prRuns", token,
            "test:(id:" + testId + "),branch:(name:pull/" + prNumber + "/head),buildType:(id:"
                + buildTypeId + "),muted:false,count:100",
            "testOccurrence(id,status,build(id,state,status,buildTypeId,buildType(name),"
                + "revisions(revision(version))," + CONDITIONS_SLOT + "))");

        if (occ == null || occ.testOccurrence() == null)
            return List.of();

        return occ.testOccurrence().stream()
            .filter(o -> o.build() != null
                && "finished".equals(o.build().state())
                && !"UNKNOWN".equals(o.build().status()))
            .sorted(Comparator.comparingLong(o -> o.build().id())) // oldest → newest
            .toList();
    }

    /**
     * The test's latest runs in one suite on every branch but the default one, newest first (up to 200),
     * with the conditions each build ran under: how the test does on other PRs' branches, which run the
     * way this PR's do. Master runs differ: the nightly RunAll runs at test scale factor 1.0, PR chains at
     * 0.1. The caller keeps the PR branches and leaves out the PR under review, so one answer serves every
     * PR. Finished, non-cancelled runs only, muted failures left out, as in {@link #prBranchRuns}.
     */
    public List<TcModel.TestOccurrence> otherBranchRuns(String token, long testId, String buildTypeId) {
        TcModel.TestOccurrences occ = occurrencesWithConditions("otherBranchRuns", token,
            "test:(id:" + testId + "),branch:(default:false),buildType:(id:" + buildTypeId + "),muted:false,count:"
                + OTHER_BRANCH_RUNS,
            "testOccurrence(status,build(id,state,status,branchName," + CONDITIONS_SLOT + "))");

        if (occ == null || occ.testOccurrence() == null)
            return List.of();

        return occ.testOccurrence().stream()
            .filter(o -> o.build() != null
                && "finished".equals(o.build().state())
                && !"UNKNOWN".equals(o.build().status()))
            .sorted(Comparator.comparingLong((TcModel.TestOccurrence o) -> o.build().id()).reversed())
            .toList();
    }

    /**
     * The test's most recent master failures in one suite (occurrence + build, newest first, up to 5).
     * Filtered client-side: a {@code status:FAILURE} locator only scans a shallow occurrence window, so it
     * misses sparse flaky failures that the plain history query does see.
     *
     * <p>Per suite, as {@link #getBaseBranchHistory}: the flaky board links them next to that suite's
     * fail rate, and a failure of the same test id on another platform is not a failure of this one.
     */
    public List<TcModel.TestOccurrence> masterFailures(String token, long testId, String buildTypeId) {
        TcModel.TestOccurrences occ = get("masterFail", token, url("app/rest/testOccurrences", query(
            "locator", "test:(id:" + testId + "),branch:(default:true),buildType:(id:" + buildTypeId + "),count:50",
            "fields", "testOccurrence(id,status,build(id,buildTypeId))")),
            TcModel.TestOccurrences.class);

        if (occ == null || occ.testOccurrence() == null)
            return List.of();

        return occ.testOccurrence().stream()
            .filter(o -> "FAILURE".equals(o.status()) && o.build() != null)
            .sorted(Comparator.comparingLong((TcModel.TestOccurrence o) -> o.build().id()).reversed())
            .limit(5)
            .toList();
    }

    /** Failure details (message/stack trace) of a single test occurrence, or null. */
    public String testDetails(String token, String occurrenceLocator) {
        TcModel.TestOccurrences occ = get("details", token, url("app/rest/testOccurrences", query(
            "locator", occurrenceLocator,
            "fields", "testOccurrence(details)")), TcModel.TestOccurrences.class);

        if (occ == null || occ.testOccurrence() == null || occ.testOccurrence().isEmpty())
            return null;

        return occ.testOccurrence().get(0).details();
    }

    /** Enqueues the RunAll chain for a PR branch. {@code top} puts it at the head of the queue. */
    public TcModel.Build triggerRunAll(String token, int prNumber, boolean top) {
        return triggerBuild(token, analysis.runAllBuildType(), prNumber, top);
    }

    /** Enqueues one build type for a PR branch. {@code top} puts it at the head of the queue. */
    public TcModel.Build triggerBuild(String token, String buildTypeId, int prNumber, boolean top) {
        return triggerBuild(token, buildTypeId, prNumber, top, "Triggered by Ignite PR Checker");
    }

    /** Same, with an explicit TeamCity trigger comment — so TC itself tells WHY a build was queued. */
    public TcModel.Build triggerBuild(String token, String buildTypeId, int prNumber, boolean top, String comment) {
        Map<String, Object> payload = Map.of(
            "branchName", "pull/" + prNumber + "/head",
            "buildType", Map.of("id", buildTypeId),
            "triggeringOptions", Map.of("queueAtTop", top),
            "comment", Map.of("text", comment));

        return recorded("trigger", () -> http.post()
            .uri(url("app/rest/buildQueue", query("fields", "id,state,branchName,buildTypeId,webUrl,buildType(name)")))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(TcModel.Build.class));
    }

    /**
     * The builds the user launched for a PR branch that are currently running or queued (running
     * first): the RunAll chain and any individually re-run suites. Snapshot-dependency suites of a
     * RunAll (there are ~150) are excluded via {@code triggered:(type:user)} — only directly
     * triggered builds are user-triggered; chain dependencies are triggered by the dependency.
     */
    public List<TcModel.Build> currentUserBuilds(String token, int prNumber) {
        List<TcModel.Build> builds = new ArrayList<>();
        builds.addAll(userBuildsInState(token, prNumber, "running"));
        builds.addAll(userBuildsInState(token, prNumber, "queued"));

        return builds;
    }

    /**
     * Cancels the given user's OWN queued/running RunAll chains for the PR — a newer commanded run
     * supersedes them, and on unchanged revisions the new chain reuses the finished suites anyway.
     * Other people's chains are never touched.
     */
    public int cancelOwnRunAllChains(String token, int prNumber, String username) {
        int cancelled = 0;
        for (TcModel.Build b : currentUserBuilds(token, prNumber)) {
            if (username.equals(starter(b)) && analysis.runAllBuildType().equals(b.buildTypeId())) {
                try {
                    cancelBuild(token, b);
                    cancelled++;
                }
                catch (RestClientResponseException e) {
                    // finished, or changed state between listing and cancelling — nothing to supersede
                }
            }
        }

        return cancelled;
    }

    /**
     * Cancels the builds {@code username} launched (RunAll or re-run suite) that are queued or running for
     * the PR and that {@code chosen} accepts; returns how many. Other people's builds are never touched:
     * ci2 lets every signed-in user cancel any build, so this is the only thing that keeps one user from
     * stopping another's chain. A refusal is thrown rather than skipped: a dead token, or a 403 on every
     * build, would otherwise read as "cancelled 0 runs".
     */
    public int cancelOwnBuilds(String token, int prNumber, String username, Predicate<TcModel.Build> chosen) {
        int cancelled = 0;
        RestClientResponseException refused = null;

        for (TcModel.Build b : currentUserBuilds(token, prNumber)) {
            if (!username.equals(starter(b)) || !chosen.test(b))
                continue;

            try {
                cancelBuild(token, b);
                cancelled++;
            }
            catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 401)
                    throw e;
                if (status == 403)
                    refused = e;
                // Any other answer: the build finished, or moved queued->running, between listing and
                // cancelling — skip it.
            }
        }

        if (cancelled == 0 && refused != null)
            throw refused;

        return cancelled;
    }

    /**
     * Re-runs a suite for {@code username}, first cancelling identical STANDALONE builds of it that the same
     * user already has in the queue for the PR branch (they'd run the same thing later for nothing).
     * Someone else's queued build of the suite stays, and the new one queues next to it: ci2 lets anyone
     * cancel anyone's build, so a click on the page must not take another person's build off the queue,
     * top place and all. Queued dependencies of a running chain are left alone — cancelling those would
     * break the chain.
     */
    public TcModel.Build triggerOwnBuildReplacingQueued(String token, String username, String buildTypeId,
        int prNumber, boolean top) {
        return triggerReplacingQueued(token, buildTypeId, prNumber, top, "Triggered by Ignite PR Checker",
            q -> username.equals(starter(q)));
    }

    /**
     * Re-runs a suite for the automation, first cancelling identical STANDALONE builds of it already
     * sitting in the queue for the same PR branch, whoever queued them (they'd run the same thing later
     * for nothing). Queued dependencies of a running chain are left alone — cancelling those would break
     * the chain. {@code comment} tells on TeamCity why the build was queued.
     */
    public TcModel.Build triggerBuildReplacingQueued(String token, String buildTypeId, int prNumber, boolean top,
        String comment) {
        return triggerReplacingQueued(token, buildTypeId, prNumber, top, comment, q -> true);
    }

    private TcModel.Build triggerReplacingQueued(String token, String buildTypeId, int prNumber, boolean top,
        String comment, Predicate<TcModel.Build> replaceable) {
        String branch = "pull/" + prNumber + "/head";
        for (TcModel.Build q : queuedBuilds(token)) {
            boolean standalone = q.triggered() != null && "user".equals(q.triggered().type());
            if (standalone && buildTypeId.equals(q.buildTypeId()) && branch.equals(q.branchName())
                && replaceable.test(q)) {
                try {
                    cancelBuild(token, q);
                }
                catch (RestClientResponseException e) {
                    // already started or gone — it is doing useful work now, leave it be
                }
            }
        }

        return triggerBuild(token, buildTypeId, prNumber, top, comment);
    }

    /** The newest finished run of one suite on the PR branch — standalone re-runs included. */
    public Optional<TcModel.Build> latestSuiteRun(String token, int prNumber, String buildTypeId) {
        String locator = "buildType:(id:" + buildTypeId + "),branch:(name:pull/" + prNumber + "/head)"
            + ",state:finished,canceled:false,failedToStart:any,count:1";

        TcModel.BuildList list = get("suiteRun", token, url("app/rest/builds", query(
            "locator", locator, "fields", "build(id,status,testOccurrences(count))")), TcModel.BuildList.class);

        return list == null || list.build() == null || list.build().isEmpty()
            ? Optional.empty() : Optional.of(list.build().get(0));
    }

    /**
     * Whether any build of any kind on the PR branch finished after {@code epochSec} — the watermark a
     * verdict was computed against. A chain's verdict is not immutable: re-running one of its suites
     * changes the answer without changing the chain's build id, so "same chain build → same result"
     * would keep showing blockers that a later green re-run already cleared. Asked by finish date
     * because TeamCity lists builds newest-START first: the first build of that list hid a suite that
     * started earlier and finished later (PR 13335: 9389217 started before 9389215, finished ten
     * minutes after it). The start-date bound is what keeps this cheap: TeamCity only filters by
     * finish date and stops walking the branch at the first finished build that started before
     * {@code startDate}, so without it the usual answer, "nothing new", read the branch's whole
     * history, up to 5000 builds.
     */
    public boolean branchFinishedAfter(String token, int prNumber, long epochSec) {
        String locator = "branch:(name:pull/" + prNumber + "/head),state:finished,canceled:any,"
            + "startDate:(date:" + TcDates.format(epochSec - LONGEST_BUILD_SECONDS) + ",condition:after),"
            + "finishDate:(date:" + TcDates.format(epochSec) + ",condition:after),count:1";

        TcModel.BuildList list = get("findBuild", token, url("app/rest/builds", query(
            "locator", locator, "fields", "build(id)")), TcModel.BuildList.class);

        return list != null && list.build() != null && !list.build().isEmpty();
    }

    /**
     * The suites with a build that finished on the PR branch after {@code epochSec}, cancelled and failed-to-start
     * ones included: the suites whose runs on the branch may have changed since. Empty when TeamCity can't say for
     * sure, having listed as many builds as it was asked for. Bounded by start date as {@link #branchFinishedAfter}
     * is. Asking for failed-to-start builds is new to ci2; if it rejects that, they are left out, as by default.
     */
    public Optional<Set<String>> suitesFinishedAfter(String token, int prNumber, long epochSec) {
        boolean failedToStart = finishedSinceFailedToStart;
        TcModel.BuildList list;
        try {
            list = buildsFinishedAfter(token, prNumber, epochSec, failedToStart);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400 || !failedToStart)
                throw e;

            list = buildsFinishedAfter(token, prNumber, epochSec, false);
            finishedSinceFailedToStart = false;
            log.warn("TeamCity rejected failedToStart:any in the lookup of a branch's finished builds ({}); "
                + "builds that failed to start are left out of it", e.getStatusText());
        }

        List<TcModel.Build> builds = list == null || list.build() == null ? List.of() : list.build();
        if (builds.size() >= FINISHED_SINCE_MAX)
            return Optional.empty();

        Set<String> suites = new HashSet<>();
        for (TcModel.Build b : builds) {
            if (b.buildTypeId() != null)
                suites.add(b.buildTypeId());
        }

        return Optional.of(suites);
    }

    private TcModel.BuildList buildsFinishedAfter(String token, int prNumber, long epochSec, boolean failedToStart) {
        String locator = "branch:(name:pull/" + prNumber + "/head),state:finished,canceled:any,"
            + (failedToStart ? "failedToStart:any," : "")
            + "startDate:(date:" + TcDates.format(epochSec - LONGEST_BUILD_SECONDS) + ",condition:after),"
            + "finishDate:(date:" + TcDates.format(epochSec) + ",condition:after),count:" + FINISHED_SINCE_MAX;

        return get("branchMoves", token, url("app/rest/builds", query(
            "locator", locator, "fields", "build(id,buildTypeId)")), TcModel.BuildList.class);
    }

    /** The user who queued a build, or null for one TeamCity queued by itself (a dependency, a VCS trigger). */
    public static String starter(TcModel.Build b) {
        return b.triggered() == null || b.triggered().user() == null ? null : b.triggered().user().username();
    }

    /** Who triggered a build — the early re-run must act under the token of whoever started the chain. */
    public Optional<String> buildTriggeredBy(String token, long buildId) {
        TcModel.Build b = get("buildState", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "triggered(type,user(username))")), TcModel.Build.class);

        return b == null ? Optional.empty() : Optional.ofNullable(starter(b));
    }

    /** Moves an already-queued build to the top of the build queue (position 1). */
    public void moveToQueueTop(String token, long buildId) {
        recorded("queueTop", () -> http.put()
            .uri(url("app/rest/buildQueue/order/1", query("fields", "id")))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("id", buildId))
            .retrieve()
            .toBodilessEntity());
    }

    /** Cancels one build, using the queue endpoint if it is still queued and the build endpoint if running. */
    public void cancelBuild(String token, TcModel.Build build) {
        String path = "queued".equalsIgnoreCase(build.state())
            ? "app/rest/buildQueue/id:" + build.id()
            : "app/rest/builds/id:" + build.id();

        recorded("cancel", () -> http.post()
            .uri(url(path, query("fields", "id,state")))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("comment", "Cancelled by Ignite PR Checker", "readdIntoQueue", false))
            .retrieve()
            .toBodilessEntity());
    }

    private List<TcModel.Build> userBuildsInState(String token, int prNumber, String state) {
        String locator = "branch:(name:pull/" + prNumber + "/head)"
            + ",triggered:(type:user),state:" + state + ",count:50";

        TcModel.BuildList list = get("userBuilds", token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,state,status,webUrl,buildTypeId,queuedDate,startDate,startEstimate,finishEstimate,buildType(name),triggered(type,user(username)),running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds))")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /** The VCS revision (git SHA) a build was run on — to tell whether the PR head has moved since. */
    public Optional<String> buildRevision(String token, long buildId) {
        TcModel.Build b = get("revision", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "revisions(revision(version))")), TcModel.Build.class);
        if (b == null || b.revisions() == null || b.revisions().revision() == null || b.revisions().revision().isEmpty())
            return Optional.empty();

        return Optional.ofNullable(b.revisions().revision().get(0).version());
    }

    /** Current state of one build (works for queued builds too — TeamCity keeps the id across the queue). */
    public TcModel.Build getBuildState(String token, long buildId) {
        return get("buildState", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,state,status,webUrl,buildTypeId,queuedDate,startDate,startEstimate,finishEstimate,buildType(name),running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds)")), TcModel.Build.class);
    }

    /** A chain build's state plus the state of each of its dependency suites — one call, for rerun tracking. */
    /**
     * Queue-aware remaining seconds for a running chain: seconds until its last dependency is
     * estimated to finish. TeamCity folds agent-queue wait into each dependency's finishEstimate,
     * whereas the composite's own running-info estimate assumes agents are free — so two chains
     * queued seconds apart can be an hour apart in reality. Returns -1 if not determinable.
     */
    public long chainRemainingSeconds(String token, long buildId) {
        return chainRemainingSeconds(token, buildId, Map.of());
    }

    /**
     * Same, with a typical-duration floor: a dependency TeamCity gives NO estimate for (an agent-starved
     * queued suite has neither a start nor a finish estimate) would otherwise silently vanish from the
     * ETA — the chain looked 15 minutes from done while a 40-minute suite had not even started. For
     * such deps the suite's typical master duration (minus elapsed time, when running) is used as an
     * honest lower bound.
     */
    public long chainRemainingSeconds(String token, long buildId, Map<String, Long> typicalDurations) {
        TcModel.Build b = get("chainEta", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "snapshot-dependencies(build(buildTypeId,state,finishEstimate,"
                + "running-info(elapsedSeconds,estimatedTotalSeconds)))")), TcModel.Build.class);

        if (b == null || b.snapshotDependencies() == null || b.snapshotDependencies().build() == null)
            return -1;

        long now = System.currentTimeMillis() / 1000;
        long max = -1;
        for (TcModel.Build dep : b.snapshotDependencies().build()) {
            if ("finished".equalsIgnoreCase(dep.state()))
                continue;

            long finish = TcDates.epochSeconds(dep.finishEstimate());
            long left = finish > 0 ? Math.max(0, finish - now) : -1;
            TcModel.RunningInfo ri = dep.runningInfo();
            if (left < 0 && ri != null && ri.estimatedTotalSeconds() != null && ri.elapsedSeconds() != null)
                left = Math.max(0, ri.estimatedTotalSeconds() - ri.elapsedSeconds());
            if (left < 0) {
                Long typical = typicalDurations.get(dep.buildTypeId());
                if (typical != null) {
                    long elapsed = ri != null && ri.elapsedSeconds() != null ? ri.elapsedSeconds() : 0;
                    left = Math.max(0, typical - elapsed);
                }
            }
            if (left > max)
                max = left;
        }
        return max;
    }

    public TcModel.Build getChainDepStates(String token, long buildId) {
        return get("buildState", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,state,status,"
                + "snapshot-dependencies(build(id,buildTypeId,state,status,webUrl,queuedDate,startDate,startEstimate,finishEstimate,buildType(name),running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds)))")), TcModel.Build.class);
    }

    /**
     * The whole build queue (bounded) — a chain's not-yet-started (non-reused) suites wait here while
     * agents are busy. ci2's queue locator has no branch dimension, so callers filter by
     * {@code branchName}; the fields are tiny, and one scan serves every tracked chain.
     */
    public List<TcModel.Build> queuedBuilds(String token) {
        TcModel.BuildList list = get("buildState", token, url("app/rest/buildQueue", query(
            "locator", "count:1000",
            "fields", "build(id,buildTypeId,state,webUrl,branchName,startEstimate,finishEstimate,buildType(name),triggered(type,user(username)))")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    /**
     * Test occurrences with their builds' run conditions, which {@code fields} asks for at
     * {@link #CONDITIONS_SLOT}. Matching two property names takes a pattern that ci2 has never been
     * asked; if it answers 400, the request is repeated naming the JDK alone, the form known to work,
     * and that form is kept. Without the fallback, a rejected pattern would fail every history request
     * and turn every failed test into an unverified blocker.
     */
    private TcModel.TestOccurrences occurrencesWithConditions(String category, String token, String locator,
        String fields) {
        String asked = runConditions;
        try {
            return get(category, token, url("app/rest/testOccurrences", query(
                "locator", locator, "fields", fields.replace(CONDITIONS_SLOT, asked))), TcModel.TestOccurrences.class);
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400 || !RUN_CONDITIONS.equals(asked))
                throw e;

            TcModel.TestOccurrences occ = get(category, token, url("app/rest/testOccurrences", query(
                "locator", locator, "fields", fields.replace(CONDITIONS_SLOT, JDK_ONLY))),
                TcModel.TestOccurrences.class);
            runConditions = JDK_ONLY;
            log.warn("TeamCity rejected the run-conditions pattern ({}); reading the JDK only, the test scale factor "
                + "stays unknown", e.getStatusText());

            return occ;
        }
    }

    private <T> T get(String category, String token, URI uri, Class<T> type) {
        return recorded(category, () -> http.get()
            .uri(uri)
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(type));
    }

    /** Runs a TeamCity call, recording its category, outcome and latency for the status page. */
    private <T> T recorded(String category, Supplier<T> call) {
        long t0 = System.nanoTime();
        try {
            T result = call.get();
            metrics.recordTc(category, true, 200, msSince(t0));

            return result;
        }
        catch (RestClientResponseException e) {
            metrics.recordTc(category, false, e.getStatusCode().value(), msSince(t0));
            throw new TcResponseException(e);
        }
        catch (RuntimeException e) {
            metrics.recordTc(category, false, 0, msSince(t0)); // network/other error
            throw OutboundHttp.naming("TeamCity", e);
        }
    }

    private static long msSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000L;
    }

    private URI url(String path, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(baseUrl).append(path);

        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(first ? '?' : '&');
            first = false;
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }

        return URI.create(sb.toString());
    }

    private static Map<String, String> query(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2)
            m.put(kv[i], kv[i + 1]);

        return m;
    }
}
