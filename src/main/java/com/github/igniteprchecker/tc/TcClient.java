package com.github.igniteprchecker.tc;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public TcClient(TeamcityProperties tc, AnalysisProperties analysis) {
        this.analysis = analysis;
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
            TcModel.User user = get(token, url("app/rest/users/current", query("fields", "username")),
                TcModel.User.class);

            return user == null ? Optional.empty() : Optional.ofNullable(user.username());
        }
        catch (RestClientResponseException e) {
            return Optional.empty();
        }
    }

    /** Latest RunAll chain build for a pull request branch ({@code pull/<n>/head}), if any. */
    public Optional<TcModel.Build> findRunAllBuildForPr(String token, int prNumber) {
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),defaultFilter:false,count:1";

        TcModel.BuildList list = get(token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,status,state,branchName)")), TcModel.BuildList.class);

        if (list == null || list.build() == null || list.build().isEmpty())
            return Optional.empty();

        return Optional.of(list.build().get(0));
    }

    /** A build with its snapshot-dependency builds expanded (the individual suites of a chain). */
    public TcModel.Build getBuildWithDeps(String token, long buildId) {
        return get(token, url("app/rest/builds/id:" + buildId, query(
            "fields", "id,status,state,branchName,buildType(id,name),"
                + "snapshot-dependencies(build(id,buildTypeId,status,state))")), TcModel.Build.class);
    }

    /** Failed test occurrences of a single build. */
    public List<TcModel.TestOccurrence> getFailedTests(String token, long buildId) {
        TcModel.TestOccurrences occ = get(token, url("app/rest/testOccurrences", query(
            "locator", "build:(id:" + buildId + "),status:FAILURE,count:2000",
            "fields", "testOccurrence(id,name,status,test(id))")), TcModel.TestOccurrences.class);

        return occ == null || occ.testOccurrence() == null ? List.of() : occ.testOccurrence();
    }

    /** Base-branch history of one test, used to compute its fail rate and flakiness. */
    public List<TcModel.TestOccurrence> getBaseBranchHistory(String token, long testId) {
        TcModel.TestOccurrences occ = get(token, url("app/rest/testOccurrences", query(
            "locator", "test:(id:" + testId + "),branch:(default:true),count:" + analysis.historyDepth(),
            "fields", "testOccurrence(status,build(id,branchName,changes(count)))")), TcModel.TestOccurrences.class);

        return occ == null || occ.testOccurrence() == null ? List.of() : occ.testOccurrence();
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

        return http.post()
            .uri(url("app/rest/buildQueue", query("fields", "id,state,branchName,buildTypeId,webUrl")))
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(TcModel.Build.class);
    }

    /** The RunAll builds currently running or queued for a PR branch (running first). */
    public List<TcModel.Build> currentRunAllBuilds(String token, int prNumber) {
        List<TcModel.Build> builds = new ArrayList<>();
        builds.addAll(runAllBuildsInState(token, prNumber, "running"));
        builds.addAll(runAllBuildsInState(token, prNumber, "queued"));

        return builds;
    }

    private List<TcModel.Build> runAllBuildsInState(String token, int prNumber, String state) {
        String locator = "buildType:" + analysis.runAllBuildType()
            + ",branch:(name:pull/" + prNumber + "/head),state:" + state + ",count:20";

        TcModel.BuildList list = get(token, url("app/rest/builds", query(
            "locator", locator,
            "fields", "build(id,state,status,webUrl)")), TcModel.BuildList.class);

        return list == null || list.build() == null ? List.of() : list.build();
    }

    private <T> T get(String token, URI uri, Class<T> type) {
        return http.get()
            .uri(uri)
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(type);
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
