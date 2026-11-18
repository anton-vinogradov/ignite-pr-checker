package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The master board names the commits between the last passing master run of a test and the first failing one. GitHub's
 * comparison lists them oldest first, each with its whole message, and the board shows the newest ones by their first
 * lines.
 */
class CommitsBetweenTest {
    private static final String PASSED = "5be1c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8";

    private static final String FAILED = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b";

    private static final String COMPARE = "https://api.github.com/repos/apache/ignite/compare/" + PASSED + "..." + FAILED;

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void theNewestCommitsComeFirstByTheFirstLineOfTheirMessages() {
        server.expect(requestTo(COMPARE)).andRespond(withSuccess("""
            {"status":"ahead","ahead_by":3,"behind_by":0,"total_commits":3,"commits":[
             {"sha":"1111111","commit":{"message":"IGNITE-29306 Remove unused methods (#13846)"}},
             {"sha":"2222222","commit":{"message":"IGNITE-29310 Rework query cancellation (#13850)\\n\\nCo-authored-by: A"}},
             {"sha":"3333333","commit":{"message":"IGNITE-29314 Enforce the lock wait timeout (#13853)\\r\\n\\r\\nDetails."}}],
             "files":[{"filename":"modules/core/pom.xml","status":"modified"}]}
            """, MediaType.APPLICATION_JSON));

        assertThat(github.commitsBetween(PASSED, FAILED, 2)).contains(new GithubClient.Commits(3, List.of(
            new GithubClient.Commit("3333333", "IGNITE-29314 Enforce the lock wait timeout (#13853)"),
            new GithubClient.Commit("2222222", "IGNITE-29310 Rework query cancellation (#13850)"))));
        server.verify();
    }

    /**
     * A run on an older commit, or on a branch rewritten since, has no commits on top of the other. Of more than 250
     * commits GitHub lists the oldest, and the board shows the newest.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"status\":\"behind\",\"total_commits\":0,\"commits\":[]}",
        "{\"status\":\"diverged\",\"total_commits\":1,\"commits\":[{\"sha\":\"1111111\",\"commit\":{\"message\":\"m\"}}]}",
        "{\"status\":\"ahead\",\"total_commits\":251,\"commits\":[{\"sha\":\"1111111\",\"commit\":{\"message\":\"m\"}}]}"})
    void aComparisonThatDoesNotListTheNewestCommitsGivesNone(String comparison) {
        server.expect(requestTo(COMPARE)).andRespond(withSuccess(comparison, MediaType.APPLICATION_JSON));

        assertThat(github.commitsBetween(PASSED, FAILED, 20)).isEmpty();
    }

    /** GitHub will not know a commit it does not know now any better later: that is an answer, not a failure. */
    @Test
    void aCommitGitHubDoesNotKnowGivesNone() {
        server.expect(requestTo(COMPARE)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(github.commitsBetween(PASSED, FAILED, 20)).isEqualTo(Optional.empty());
    }

    /** An outage is no answer: the caller hears of it, and asks again later. */
    @Test
    void anOutageIsNotTakenForNoCommits() {
        server.expect(requestTo(COMPARE)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> github.commitsBetween(PASSED, FAILED, 20)).hasMessageContaining("502");
    }
}
