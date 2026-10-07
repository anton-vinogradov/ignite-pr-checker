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

/** The PR's own test classes, with whether each is new, come from one call for its files. */
class PrTestFilesTest {
    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void theTestClassesAPrAddsOrChanges() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/pulls/13327/files?per_page=100&page=1"))
            .andRespond(withSuccess("""
                [{"filename":"modules/core/src/main/java/org/apache/ignite/spi/discovery/tcp/ClientImpl.java",
                  "status":"modified"},
                 {"filename":"modules/core/src/test/java/org/apache/ignite/spi/discovery/tcp/TcpDiscoveryClientTopologyGapTest.java",
                  "status":"added"},
                 {"filename":"modules/core/src/test/java/org/apache/ignite/testsuites/IgniteSpiDiscoverySelfTestSuite.java",
                  "status":"modified"},
                 {"filename":"modules/core/src/test/java/org/apache/ignite/spi/discovery/tcp/OldGapTest.java",
                  "status":"removed"},
                 {"filename":"modules/core/src/test/java/org/apache/ignite/util/GridCommandHandlerTest.java",
                  "status":"modified"}]
                """, MediaType.APPLICATION_JSON));

        assertThat(github.prTestFiles(13327)).containsExactly(
            new GithubClient.PrFile("modules/core/src/test/java/org/apache/ignite/spi/discovery/tcp/"
                + "TcpDiscoveryClientTopologyGapTest.java", "added"),
            new GithubClient.PrFile("modules/core/src/test/java/org/apache/ignite/util/GridCommandHandlerTest.java",
                "modified"));
        server.verify();
    }
}
