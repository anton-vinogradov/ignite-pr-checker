package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Any ci2 user may press Post to PR on any PR, and each press is a write of the checker's account to the PR, which may
 * write there 40 times an hour. mallory pressed it 40 times on the final RunAll 9389046 of PR #13335: the account then
 * had no write left for the PR that hour, and the reply to bob's {@code /run-all} there had to wait. The checker takes
 * 5 presses on a PR in an hour, and the rest of the PR's writes stay for its stories and verdict comments.
 */
class PostToPrBudgetTest {
    private static final int PR = 13335;

    private static final long RUN = 9389046L;

    private static final String COMMENTS = "https://api.github.com/repos/apache/ignite/issues/13335/comments";

    private static final String EDIT = "https://api.github.com/repos/apache/ignite/issues/comments/777";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300),
        mapper, new Metrics(mapper), http.build());

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), new VisaService(new TeamcityProperties(
        "https://ci2.example/"), "https://checker.example"), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class), new SettingsJournal(mapper));

    @Test
    void pressesLeaveTheWritesOfThePrToItsStories() {
        server.expect(once(), requestTo(COMMENTS)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(
            "{\"id\":777,\"html_url\":\"https://github.com/apache/ignite/pull/13335#issuecomment-777\"}",
            MediaType.APPLICATION_JSON));
        server.expect(times(StandingVisas.PRESSES_PER_PR_HOUR - 1), requestTo(EDIT))
            .andExpect(method(HttpMethod.PATCH)).andRespond(withSuccess());
        server.expect(once(), requestTo(COMMENTS)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(
            "{\"id\":778,\"html_url\":\"https://github.com/apache/ignite/pull/13335#issuecomment-778\"}",
            MediaType.APPLICATION_JSON));

        for (int i = 0; i < AppWrites.PER_PR_HOUR; i++) {
            standing.postCommentFromPage("mallory", SettingsJournal.By.of("laptop", "Firefox on Linux"), "tc", PR,
                RUN, clean(), null);
        }
        GithubClient.PostedComment reply = github.appComment(PR, "bob", "Your RunAll is queued.");

        assertThat(reply.id()).isEqualTo(778L);
        server.verify();
    }

    private static AnalysisResult clean() {
        return new AnalysisResult(PR, RUN, "pull/13335/head", 1_800_000_000_000L, List.of(), List.of(), List.of(),
            List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
