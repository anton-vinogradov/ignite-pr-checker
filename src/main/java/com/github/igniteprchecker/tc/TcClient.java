package com.github.igniteprchecker.tc;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin wrapper over the TeamCity REST API. Stateless with respect to auth: every call takes the
 * caller's personal token, so requests run with that user's TeamCity permissions.
 *
 * <p>Query values are fully percent-encoded by hand: the TeamCity WAF rejects raw {@code (}/{@code )}
 * (and {@code [}/{@code ]}) in the URL, which the default Spring query encoder leaves untouched.
 */
@Component
public class TcClient {
    private final RestClient http;

    private final String baseUrl;

    private final AnalysisProperties analysis;

    public TcClient(TeamcityProperties tc, AnalysisProperties analysis) {
        this.analysis = analysis;
        this.baseUrl = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        this.http = RestClient.builder()
            .defaultHeader("Accept", "application/json")
            .build();
    }

    /** @return {@code true} if the token authenticates against TeamCity. */
    public boolean validateToken(String token) {
        try {
            http.get()
                .uri(url("app/rest/users/current", Map.of()))
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();

            return true;
        }
        catch (RestClientResponseException e) {
            return false;
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
