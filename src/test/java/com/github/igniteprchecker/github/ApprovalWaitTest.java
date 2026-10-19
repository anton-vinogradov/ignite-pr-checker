package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.AbandonedTestsCheck;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.PrTestRuns;
import com.github.igniteprchecker.analysis.model.PrTests.SuiteCheck.State;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.TcClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * apache/ignite asks a maintainer to approve the workflow runs of every push by a contributor outside the project. Till
 * then GitHub shows the commit's run of "Code Style, Abandoned Tests, Javadocs" as a check suite of GitHub Actions,
 * completed with the conclusion "action_required" and no check runs, as it did for commit c41111c5 on 14.10, whose only
 * check came from another workflow. The PR page and Suite fix said such a commit conflicts with master.
 */
class ApprovalWaitTest {
    private static final String SHA = "c41111c5b07d3e2a9f8e61c4d2b7a90e53f1d8c6";

    private static final String CHECK_RUNS = """
        {"total_count": 1, "check_runs": [{"id": 52817760931, "name": "Rolling Upgrade check",
          "head_sha": "c41111c5b07d3e2a9f8e61c4d2b7a90e53f1d8c6", "status": "completed", "conclusion": "success",
          "html_url": "https://github.com/apache/ignite/actions/runs/18620011112/job/52817760931",
          "app": {"id": 15368, "slug": "github-actions", "name": "GitHub Actions"}}]}
        """;

    private static final String CHECK_SUITES = """
        {"total_count": 2, "check_suites": [
          {"id": 104534382790, "head_branch": "IGNITE-29010", "head_sha": "c41111c5b07d3e2a9f8e61c4d2b7a90e53f1d8c6",
           "status": "completed", "conclusion": "success", "latest_check_runs_count": 1,
           "app": {"id": 15368, "slug": "github-actions", "name": "GitHub Actions"}},
          {"id": 104534382801, "head_branch": "IGNITE-29010", "head_sha": "c41111c5b07d3e2a9f8e61c4d2b7a90e53f1d8c6",
           "status": "completed", "conclusion": "action_required", "latest_check_runs_count": 0,
           "rerequestable": true, "runs_rerequestable": false,
           "app": {"id": 15368, "slug": "github-actions", "name": "GitHub Actions"}}]}
        """;

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    @Test
    void theCheckSaysItWaitsForAMaintainer() {
        GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300), mapper,
            new Metrics(mapper), http.build());
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/commits/" + SHA
            + "/check-runs?per_page=100")).andRespond(withSuccess(CHECK_RUNS, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/commits/" + SHA
            + "/check-suites?per_page=100")).andRespond(withSuccess(CHECK_SUITES, MediaType.APPLICATION_JSON));

        AbandonedTestsCheck.Outcome check = new PrTestRuns(github, mock(TcClient.class), mock(AnalysisCache.class))
            .suiteCheck(SHA);

        assertThat(check.state()).isEqualTo(State.UNKNOWN);
        assertThat(check.reason()).isEqualTo("it waits for a maintainer to approve its workflow run");
        server.verify();
    }
}
