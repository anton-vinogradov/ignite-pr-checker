package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * PR 13335's SslRenewalTest moved to another package after the RunAll the page showed, and SecurityTestSuite changed
 * with it. Only GitHub's status of each file in the comparison tells a class new under its name from one the run had.
 */
class ChangesBetweenTest {
    private static final String BUILT = "5be1c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8";

    private static final String HEAD = "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void eachFileComesWithItsStatus() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/compare/" + BUILT + "..." + HEAD))
            .andRespond(withSuccess("""
                {"status":"ahead","ahead_by":2,"behind_by":0,"files":[
                 {"filename":"modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java",
                  "previous_filename":"modules/core/src/test/java/org/apache/ignite/ssl/SslRenewalTest.java",
                  "status":"renamed"},
                 {"filename":"modules/core/src/test/java/org/apache/ignite/testsuites/SecurityTestSuite.java",
                  "status":"modified"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(github.changesBetween(BUILT, HEAD)).isEqualTo(new GithubClient.Changes(Map.of(
            "modules/core/src/test/java/org/apache/ignite/internal/ssl/SslRenewalTest.java", "renamed",
            "modules/core/src/test/java/org/apache/ignite/testsuites/SecurityTestSuite.java", "modified"), false, true));
        server.verify();
    }
}
