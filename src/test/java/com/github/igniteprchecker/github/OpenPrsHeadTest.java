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
 * The PR list ticked runs of old code: 7 of 13 green ticks on prod were for runs with commits pushed since. The
 * head each PR has now comes with the list GitHub already sends, so telling them apart costs no call.
 */
class OpenPrsHeadTest {
    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void eachListedPrComesWithItsHead() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls?state=open&sort=updated"
            + "&direction=desc&per_page=50")).andRespond(withSuccess("""
                [{"number":13636,"title":"IGNITE-1 x","html_url":"https://github.com/apache/ignite/pull/13636",
                  "head":{"ref":"ignite-1","sha":"5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4"}},
                 {"number":13593,"title":"IGNITE-2 y","html_url":"https://github.com/apache/ignite/pull/13593"}]
                """, MediaType.APPLICATION_JSON));

        assertThat(github.openPrs()).extracting(PrSummary::headSha)
            .containsExactly("5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4", null);
        server.verify();
    }
}
