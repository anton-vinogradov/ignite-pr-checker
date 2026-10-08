package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.PrTests;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A PR whose fork was deleted: GitHub gives its head's sha, and {@code "repo": null} for the fork. Ignite's
 * abandoned-tests check of that head was not read, as the fork's name was asked for too, and the card said "GitHub
 * could not name the PR's head".
 */
class DeletedForkHeadTest {
    private static final String SHA = "9389046a1b2c3d4e5f60718293a4b5c6d7e8f901";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300),
        mapper, new Metrics(mapper), http.build());

    @Test
    void ignitesCheckOfTheHeadIsReadWithoutTheFork() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/12000/files?per_page=100&page=1"))
            .andRespond(withSuccess("""
                [{"filename":"modules/core/src/test/java/org/apache/ignite/FooTest.java","status":"added"}]
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/12000"))
            .andRespond(withSuccess("""
                {"number":12000,"state":"open","merged_at":null,"title":"IGNITE-1 Foo","user":{"login":"someone"},
                 "head":{"label":"unknown:ignite-1","ref":"ignite-1","sha":"%s","repo":null}}
                """.formatted(SHA), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/commits/" + SHA
            + "/check-runs?per_page=100")).andRespond(withSuccess("""
                {"total_count":1,"check_runs":[{"id":7,"name":"Check java code on JDK 17","status":"completed",
                 "conclusion":"success","html_url":"https://github.com/apache/ignite/actions/runs/1/job/7"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/actions/jobs/7"))
            .andRespond(withSuccess("""
                {"id":7,"status":"completed","steps":[
                 {"name":"Run abandoned tests checks.","status":"completed","conclusion":"success"}]}
                """, MediaType.APPLICATION_JSON));

        PrTests answer = new PrTestRuns(github, mock(TcClient.class),
            new AnalysisCache(new AnalysisProperties(null, "RunAll", null, null, null, null, null), mapper))
            .of("tok", 12000, 1L, false);

        assertThat(answer.suiteCheck().state()).isEqualTo(PrTests.SuiteCheck.State.PASSED);
        assertThat(answer.suiteCheck().sha()).isEqualTo(SHA);
        server.verify();
    }
}
