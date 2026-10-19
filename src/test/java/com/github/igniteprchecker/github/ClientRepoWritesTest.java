package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The checker answers commands in PR #69 of apache/ignite-python-thin-client while apache/ignite has a PR #69 too. Its
 * writes go to the client's repo, and the hourly cap of 40 writes to one PR counts the two PRs apart: a busy Ignite
 * #69 must not hold back the client's verdict. The client's heads come from GitHub: the PR's and Ignite master's.
 */
class ClientRepoWritesTest {
    private static final String API = "https://api.github.com/repos/";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300),
        mapper, new Metrics(mapper), http.build());

    @Test
    void aFullHourOfWritesToIgnitesPrDoesNotHoldBackTheClientsPrOfTheSameNumber() {
        server.expect(times(AppWrites.PER_PR_HOUR), requestTo(API + "apache/ignite/issues/comments/7/reactions"))
            .andExpect(method(HttpMethod.POST)).andRespond(withSuccess());
        server.expect(once(), requestTo(API + "apache/ignite-python-thin-client/issues/69/comments"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"id\":88,\"html_url\":\"https://github.com/c\"}", MediaType.APPLICATION_JSON));

        for (int i = 0; i < AppWrites.PER_PR_HOUR; i++)
            github.appReact(69, null, 7L, "rocket");
        GithubClient.PostedComment posted = github.appComment("apache/ignite-python-thin-client", 69, null, "verdict");

        server.verify();
        assertThat(posted.id()).isEqualTo(88L);
    }

    @Test
    void theHeadsOfTheClientsPrAndOfIgniteMasterComeFromGithub() {
        server.expect(once(), requestTo(API + "apache/ignite-python-thin-client/pulls/69"))
            .andRespond(withSuccess("{\"head\":{\"sha\":\"a1b2c3d\"}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API + "apache/ignite/git/ref/heads/master"))
            .andRespond(withSuccess("{\"object\":{\"sha\":\"1f2e3d4\"}}", MediaType.APPLICATION_JSON));

        assertThat(github.prHeadSha("apache/ignite-python-thin-client", 69)).isEqualTo("a1b2c3d");
        assertThat(github.branchHeadSha("apache/ignite", "master")).isEqualTo("1f2e3d4");
        server.verify();
    }

    @Test
    void theClientsCommentsAreReadFromItsRepo() {
        server.expect(once(), requestTo(API + "apache/ignite-python-thin-client/issues/comments?since="
            + "2026-10-19T12:00:00Z&sort=updated&direction=asc&per_page=100&page=1"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(github.recentIssueComments("apache/ignite-python-thin-client", "2026-10-19T12:00:00Z")).isEmpty();
        server.verify();
    }
}
