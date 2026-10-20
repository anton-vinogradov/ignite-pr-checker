package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The page of a merged or closed PR tags its head with Merged or Closed, and the tag says when and at which commit:
 * apache/ignite#13709 was merged on 20 Oct at 10:12:39 UTC at its commit 9b02561. One call gives it all.
 */
class PullStateTest {
    private static final String HEAD = "9b02561a7c3e5f9d1b2a4c6e8f0a1b3c5d7e9f2a";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void aMergedPrSaysWhenAndAtWhichCommit() {
        answer(13709, """
            {"number":13709,"state":"closed","title":"IGNITE-29120 Fix control.sh","merged":true,
             "merged_at":"2026-10-20T10:12:39Z","closed_at":"2026-10-20T10:12:40Z","head":{"sha":"%s"}}
            """.formatted(HEAD));

        assertThat(github.pullState(13709)).contains(
            new GithubClient.PullState("IGNITE-29120 Fix control.sh", false, true, 1_792_491_159L, HEAD));
    }

    @Test
    void aPrClosedWithoutAMergeSaysWhenItWasClosed() {
        answer(13710, """
            {"number":13710,"state":"closed","title":"x","merged":false,"merged_at":null,
             "closed_at":"2026-10-20T10:12:39Z","head":{"sha":"def4567"}}
            """);

        assertThat(github.pullState(13710)).contains(new GithubClient.PullState("x", false, false, 1_792_491_159L,
            "def4567"));
    }

    @Test
    void anOpenPrHasNoCloseTime() {
        answer(13711, """
            {"number":13711,"state":"open","title":"y","merged":false,"merged_at":null,"closed_at":null,
             "head":{"sha":"abc1234"}}
            """);

        assertThat(github.pullState(13711)).contains(new GithubClient.PullState("y", true, false, 0, "abc1234"));
    }

    private void answer(int pr, String json) {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/" + pr))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }
}
