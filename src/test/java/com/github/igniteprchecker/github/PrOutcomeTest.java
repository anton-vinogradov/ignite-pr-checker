package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A merged PR keeps the verdict it had at the merge, as master's history soon holds its own failures. Whether it
 * was merged, when and as which commit is one call.
 */
class PrOutcomeTest {
    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void aMergedPrSaysWhenAndAsWhichCommit() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13566")).andRespond(withSuccess("""
            {"number":13566,"state":"closed","title":"IGNITE-28890 Fix it","merged_at":"2026-10-05T14:02:00Z",
             "merge_commit_sha":"c0ffee1","head":{"sha":"5be1c0d"}}
            """, MediaType.APPLICATION_JSON));

        assertThat(github.prOutcome(13566)).isEqualTo(
            new GithubClient.PrOutcome(true, true, 1_791_208_920L, "c0ffee1", "5be1c0d", "IGNITE-28890 Fix it"));
    }

    @Test
    void aClosedPrWasNotMerged() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13567")).andRespond(withSuccess("""
            {"number":13567,"state":"closed","title":"x","merged_at":null,"merge_commit_sha":"abc",
             "head":{"sha":"def"}}
            """, MediaType.APPLICATION_JSON));

        GithubClient.PrOutcome outcome = github.prOutcome(13567);

        assertThat(outcome.merged()).isFalse();
        assertThat(outcome.closed()).isTrue();
    }
}
