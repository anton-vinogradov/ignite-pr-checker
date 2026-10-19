package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * After his /run-all on apache/ignite#13636, Nikolay Izhikov asked whether a PR with merge conflicts should get a
 * RunAll at all. GitHub names conflicts with the base branch as {@code "mergeable": false}. Until it has worked them
 * out it names none ({@code null}), and the checker does not wait for that.
 */
class MergeConflictsTest {
    private static final String PULL = "https://api.github.com/repos/apache/ignite/pulls/13636";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void aPrThatCannotBeMergedHasConflicts() {
        answer("{\"number\":13636,\"state\":\"open\",\"mergeable\":false,\"mergeable_state\":\"dirty\"}");

        assertThat(github.reportsConflicts(13636)).isTrue();
    }

    @Test
    void aPrThatCanBeMergedHasNone() {
        answer("{\"number\":13636,\"state\":\"open\",\"mergeable\":true,\"mergeable_state\":\"clean\"}");

        assertThat(github.reportsConflicts(13636)).isFalse();
    }

    @Test
    void aPrGithubHasNotCheckedYetIsAskedOnceAndHasNone() {
        answer("{\"number\":13636,\"state\":\"open\",\"mergeable\":null,\"mergeable_state\":\"unknown\"}");

        assertThat(github.reportsConflicts(13636)).isFalse();
        server.verify();
    }

    @Test
    void noAnswerFromGithubIsNoConflicts() {
        server.expect(once(), requestTo(PULL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(github.reportsConflicts(13636)).isFalse();
    }

    @Test
    void aPrGithubDoesNotHaveHasNone() {
        server.expect(once(), requestTo(PULL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(github.reportsConflicts(13636)).isFalse();
    }

    @Test
    void aPrOfAnotherRepoIsAskedThere() {
        server.expect(once(), requestTo("https://api.github.com/repos/apache/ignite-python-thin-client/pulls/69"))
            .andRespond(withSuccess("{\"number\":69,\"mergeable\":false}", MediaType.APPLICATION_JSON));

        assertThat(github.reportsConflicts("apache/ignite-python-thin-client", 69)).isTrue();
    }

    private void answer(String pull) {
        server.expect(once(), requestTo(PULL)).andRespond(withSuccess(pull, MediaType.APPLICATION_JSON));
    }
}
