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
            + ",branch:(name:pull/" + prNumber + "/head),state:finished,canceled:false,count:1";

        TcModel.BuildList list = get("findBuild", token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,status,state,branchName,triggered(type,user(username)))")), TcModel.BuildList.class);

        if (list == null || list.build() == null || list.build().isEmpty())
            return Optional.empty();

        return Optional.of(list.build().get(0));
    }

    /** A build with its snapshot-dependency builds expanded (the individual suites of a chain). */
    public TcModel.Build getBuildWithDeps(String token, long buildId) {
        return get("deps", token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,status,state,branchName,buildType(id,name),"
                + "snapshot-dependencies(build(id,buildTypeId,status,state,buildType(name)))")), TcModel.Build.class);
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
        Map<String, Object> payload = Map.of(
            "branchName", "pull/" + prNumber + "/head",
            "buildType", Map.of("id", buildTypeId),
            "triggeringOptions", Map.of("queueAtTop", top),
            "comment", Map.of("text", "Triggered by Ignite PR Checker"));

        return recorded("trigger", () -> http.post()
            .uri(url("app/rest/buildQueue", query("fields", "id,state,branchName,buildTypeId,webUrl")))
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
            "fields", "build(id,state,status,webUrl,buildType(name))")), TcModel.BuildList.class);

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
