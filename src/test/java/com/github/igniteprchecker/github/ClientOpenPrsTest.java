package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
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
 * The PR list has a group of the Python thin client's open PRs, about 7 of them, under Ignite's. Their list is asked of
 * GitHub apart and kept apart: PR #69 of the client is not Ignite's #69. Once its last PR is merged the client has none
 * open, and that answer stands.
 */
class ClientOpenPrsTest {
    private static final String CLIENT_PRS = "https://api.github.com/repos/apache/ignite-python-thin-client/pulls"
        + "?state=open&sort=updated&direction=desc&per_page=50";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void theClientsPrsAreListedAndKeptApartFromIgnites() {
        server.expect(once(), requestTo(CLIENT_PRS)).andRespond(withSuccess("""
            [{"number":69,"title":"IGNITE-29117 Fix test","html_url":"https://github.com/apache/ignite-python-thin-client/pull/69",
              "head":{"ref":"h2","sha":"a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4"}}]
            """, MediaType.APPLICATION_JSON));

        assertThat(github.openPrs("apache/ignite-python-thin-client")).singleElement().satisfies(p -> {
            assertThat(p.number()).isEqualTo(69);
            assertThat(p.headSha()).isEqualTo("a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4");
        });
        assertThat(github.openPrs("apache/ignite-python-thin-client")).hasSize(1);
        assertThat(github.listedHead(69, 0)).isNull();
        server.verify();
    }

    @Test
    void noOpenPrIsAnAnswerToo() {
        server.expect(once(), requestTo(CLIENT_PRS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(github.openPrs("apache/ignite-python-thin-client")).isEmpty();
        assertThat(github.openPrs("apache/ignite-python-thin-client")).isEmpty();
        server.verify();
    }

    @Test
    void githubDownGivesNoPrsAndNoError() {
        server.expect(once(), requestTo(CLIENT_PRS)).andRespond(withServerError());

        assertThat(github.openPrs("apache/ignite-python-thin-client")).isEmpty();
        server.verify();
    }
}
