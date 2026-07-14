package com.github.igniteprchecker.tc;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final RestClient http;

    private final String baseUrl;

    private final AnalysisProperties analysis;

    private final Metrics metrics;

    public TcClient(TeamcityProperties tc, AnalysisProperties analysis, Metrics metrics) {
        this.analysis = analysis;
        this.metrics = metrics;
        this.baseUrl = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        // Must use SimpleClientHttpRequestFactory (HttpURLConnection): the default JDK factory sends
        // "Content-Length: 0" on GET, which the TeamCity WAF rejects with 403 "Access Blocked".
        this.http = RestClient.builder()
            .requestFactory(new SimpleClientHttpRequestFactory())
            .defaultHeader("Accept", "application/json")
            .build();
    }

    /** The TeamCity username the token belongs to, or empty if the token is not accepted. */
    public Optional<String> currentUsername(String token) {
        try {
            TcModel.User user = get("whoami", token, url("app/rest/users/current", query("fields", "username")),
                TcModel.User.class);

            return user == null ? Optional.empty() : Optional.ofNullable(user.username());
        }
        catch (RestClientResponseException e) {
            return Optional.empty();
        }
    }

    /**
     * Latest <em>finished, non-cancelled</em> RunAll chain build for a pull request branch
     * ({@code pull/<n>/head}), if any. Cancelled runs (status {@code UNKNOWN}) and still-running or
     * queued builds are skipped: their test results are absent or partial, so analysing them would
     * wrongly report zero failures. In particular this stops a freshly triggered (or a cancelled)
     * run from shadowing the last real verdict.
     */
    public Optional<TcModel.Build> findRunAllBuildForPr(String token, int prNumber) {
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),state:finished,canceled:false,failedToStart:any,count:1";

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

    /** A build with its snapshot-dependency builds expanded (the individual suites of a chain). */
    public TcModel.Build getBuildWithDeps(String token, long buildId) {
        return get("deps", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,status,state,branchName,queuedDate,buildType(id,name),"
                + "snapshot-dependencies(build(id,buildTypeId,status,state,queuedDate,buildType(name),"
                + "problemOccurrences(problemOccurrence(type,details))))")), TcModel.Build.class);
    }

    /** Failed test occurrences of a single build. */
    public List<TcModel.TestOccurrence> getFailedTests(String token, long buildId) {
        TcModel.TestOccurrences occ = get("failedTests", token, url("app/rest/testOccurrences", query(
            "locator", "build:(id:" + buildId + "),status:FAILURE,count:2000",
            "fields", "testOccurrence(id,name,status,test(id))")), TcModel.TestOccurrences.class);

        return occ == null || occ.testOccurrence() == null ? List.of() : occ.testOccurrence();
    }

    /** Recent master history of one test (up to {@code analysis.historyDepth} runs): just the statuses. */
    public List<TcModel.TestOccurrence> getBaseBranchHistory(String token, long testId) {
        TcModel.TestOccurrences occ = get("history", token, url("app/rest/testOccurrences", query(
            "locator", "test:(id:" + testId + "),branch:(default:true),count:" + analysis.historyDepth(),
            "fields", "testOccurrence(status)")), TcModel.TestOccurrences.class);

        return occ == null || occ.testOccurrence() == null ? List.of() : occ.testOccurrence();
    }

    /**
     * The test's <em>finished, non-cancelled</em> runs on the PR branch, oldest → newest (up to 100 —
     * effectively every run of any real PR). The last element is the latest completed run (a blocker
     * must still be FAILURE there — a passing re-run clears it); the whole sequence backs the
     * per-blocker pass/fail history strip. One request.
     */
    public List<TcModel.TestOccurrence> prBranchRuns(String token, int prNumber, long testId) {
        TcModel.TestOccurrences occ = get("prRuns", token, url("app/rest/testOccurrences", query(
            "locator", "test:(id:" + testId + "),branch:(name:pull/" + prNumber + "/head),count:100",
            "fields", "testOccurrence(id,status,build(id,state,status,buildTypeId,buildType(name)))")),
            TcModel.TestOccurrences.class);

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
     * The test's most recent master failures (occurrence + build, newest first, up to 5). Filtered
     * client-side: a {@code status:FAILURE} locator only scans a shallow occurrence window, so it
     * misses sparse flaky failures that the plain history query does see.
     */
    public List<TcModel.TestOccurrence> masterFailures(String token, long testId) {
        TcModel.TestOccurrences occ = get("masterFail", token, url("app/rest/testOccurrences", query(
            "locator", "test:(id:" + testId + "),branch:(default:true),count:50",
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

    /** Cancels every user-launched build (RunAll or re-run suite) currently queued or running; returns how many. */
    public int cancelUserBuilds(String token, int prNumber) {
        int cancelled = 0;

        for (TcModel.Build b : currentUserBuilds(token, prNumber)) {
            try {
                cancelBuild(token, b);
                cancelled++;
            }
            catch (RestClientResponseException e) {
                // The build finished, or moved queued->running, between listing and cancelling: skip it.
            }
        }

        return cancelled;
    }

    /** Cancels one build, using the queue endpoint if it is still queued and the build endpoint if running. */
    /**
     * Re-runs a suite, first cancelling identical STANDALONE builds of it already sitting in the
     * queue for the same PR branch (they'd run the same thing later for nothing). Queued dependencies
     * of a running chain are left alone — cancelling those would break the chain.
     */
    public TcModel.Build triggerBuildReplacingQueued(String token, String buildTypeId, int prNumber, boolean top) {
        return triggerBuildReplacingQueued(token, buildTypeId, prNumber, top, "Triggered by Ignite PR Checker");
    }

    /** Same, with an explicit TeamCity trigger comment. */
    public TcModel.Build triggerBuildReplacingQueued(String token, String buildTypeId, int prNumber, boolean top,
        String comment) {
        String branch = "pull/" + prNumber + "/head";
        for (TcModel.Build q : queuedBuilds(token)) {
            boolean standalone = q.triggered() != null && "user".equals(q.triggered().type());
            if (standalone && buildTypeId.equals(q.buildTypeId()) && branch.equals(q.branchName())) {
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
            "locator", locator, "fields", "build(id,status)")), TcModel.BuildList.class);

        return list == null || list.build() == null || list.build().isEmpty()
            ? Optional.empty() : Optional.of(list.build().get(0));
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
            "fields", "build(id,state,status,webUrl,buildTypeId,startEstimate,finishEstimate,buildType(name),running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds))")), TcModel.BuildList.class);

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
            "fields", "id,state,status,webUrl,buildTypeId,startEstimate,finishEstimate,buildType(name),running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds)")), TcModel.Build.class);
    }

    /** A chain build's state plus the state of each of its dependency suites — one call, for rerun tracking. */
    /**
     * Queue-aware remaining seconds for a running chain: seconds until its last dependency is
     * estimated to finish. TeamCity folds agent-queue wait into each dependency's finishEstimate,
     * whereas the composite's own running-info estimate assumes agents are free — so two chains
     * queued seconds apart can be an hour apart in reality. Returns -1 if not determinable.
     */
    public long chainRemainingSeconds(String token, long buildId) {
        TcModel.Build b = get("chainEta", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "snapshot-dependencies(build(state,finishEstimate,"
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
            if (left < 0) {
                TcModel.RunningInfo ri = dep.runningInfo();
                if (ri != null && ri.estimatedTotalSeconds() != null && ri.elapsedSeconds() != null)
                    left = Math.max(0, ri.estimatedTotalSeconds() - ri.elapsedSeconds());
            }
            if (left > max)
                max = left;
        }
        return max;
    }

    public TcModel.Build getChainDepStates(String token, long buildId) {
        return get("buildState", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,state,status,"
                + "snapshot-dependencies(build(id,buildTypeId,state,status,webUrl,startEstimate,finishEstimate,buildType(name),running-info(percentageComplete,elapsedSeconds,estimatedTotalSeconds)))")), TcModel.Build.class);
    }

    /**
     * The whole build queue (bounded) — a chain's not-yet-started (non-reused) suites wait here while
     * agents are busy. ci2's queue locator has no branch dimension, so callers filter by
     * {@code branchName}; the fields are tiny, and one scan serves every tracked chain.
     */
    public List<TcModel.Build> queuedBuilds(String token) {
        TcModel.BuildList list = get("buildState", token, url("app/rest/buildQueue", query(
            "locator", "count:1000",
            "fields", "build(id,buildTypeId,state,webUrl,branchName,startEstimate,finishEstimate,buildType(name),triggered(type))")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
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
            throw e;
        }
        catch (RuntimeException e) {
            metrics.recordTc(category, false, 0, msSince(t0)); // network/other error
            throw e;
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
