package com.github.igniteprchecker.github;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Ignite's own check for test classes in no suite runs in its GitHub Actions job "Check java code on JDK 17", and the
 * classes are named only in the job's log. The commit's checks, the job's steps and the log are read from GitHub; the
 * log comes from another host, where the checker's token must not go.
 */
class ActionsJobTest {
    private static final String SHA = "9389046a1b2c3d4e5f60718293a4b5c6d7e8f901";

    private static final String LOG_URL = "https://results-receiver.actions.githubusercontent.com/logs/51234567890"
        + "?sig=abc";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300),
        mapper, new Metrics(mapper), http.build());

    @Test
    void theChecksOfACommit() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/commits/" + SHA + "/check-runs?per_page=100"))
            .andRespond(withSuccess("""
                {"total_count":2,"check_runs":[
                 {"id":51234567890,"name":"Check java code on JDK 17","status":"completed","conclusion":"failure",
                  "html_url":"https://github.com/apache/ignite/actions/runs/18001/job/51234567890",
                  "app":{"slug":"github-actions"}},
                 {"id":51234567891,"name":"Check ducktape on py38","status":"in_progress","conclusion":null,
                  "html_url":"https://github.com/apache/ignite/actions/runs/18001/job/51234567891"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(github.checkRuns(SHA)).containsExactly(
            new GithubClient.CheckRun(51234567890L, "Check java code on JDK 17", "completed", "failure",
                "https://github.com/apache/ignite/actions/runs/18001/job/51234567890"),
            new GithubClient.CheckRun(51234567891L, "Check ducktape on py38", "in_progress", null,
                "https://github.com/apache/ignite/actions/runs/18001/job/51234567891"));
        server.verify();
    }

    @Test
    void theStepsOfAJob() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/actions/jobs/51234567890"))
            .andRespond(withSuccess("""
                {"id":51234567890,"status":"completed","conclusion":"failure","steps":[
                 {"name":"Run codestyle and licenses checks","status":"completed","conclusion":"success","number":6},
                 {"name":"Run abandoned tests checks.","status":"completed","conclusion":"failure","number":7},
                 {"name":"Check javadocs.","status":"completed","conclusion":"skipped","number":8}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(github.job(51234567890L)).isEqualTo(new GithubClient.Job("completed", List.of(
            new GithubClient.Job.Step("Run codestyle and licenses checks", "completed", "success"),
            new GithubClient.Job.Step("Run abandoned tests checks.", "completed", "failure"),
            new GithubClient.Job.Step("Check javadocs.", "completed", "skipped"))));
        server.verify();
    }

    @Test
    void theLogIsReadFromWhereGithubSendsItWithoutTheToken() {
        server.expect(requestTo("https://api.github.com/repos/apache/ignite/actions/jobs/51234567890/logs"))
            .andExpect(header("Authorization", "Bearer app-token"))
            .andRespond(withStatus(HttpStatus.FOUND).location(URI.create(LOG_URL)));
        server.expect(requestTo(LOG_URL))
            .andExpect(headerDoesNotExist("Authorization"))
            .andRespond(withSuccess("2026-09-29T14:46:58.0103602Z first\n2026-09-29T14:46:58.0103711Z second\n",
                MediaType.TEXT_PLAIN));

        assertThat(github.jobLog(51234567890L, lines -> lines.toList())).hasValue(List.of(
            "2026-09-29T14:46:58.0103602Z first", "2026-09-29T14:46:58.0103711Z second"));
        server.verify();
    }

    @Test
    void withoutTheAppTokenNoLogIsAskedFor() {
        GithubClient anonymous = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
            new Metrics(mapper), http.build());

        assertThat(anonymous.jobLog(51234567890L, lines -> lines.toList())).isEmpty();
        server.verify();
    }

    /**
     * The service's own HTTP client follows redirects. Had it followed this one, it would have sent the checker's
     * GitHub token to the host that keeps the logs.
     */
    @Test
    void theServicesClientSendsTheTokenOnlyToGithub() throws IOException {
        HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        Map<String, String> authorization = new ConcurrentHashMap<>();
        String base = "http://127.0.0.1:" + api.getAddress().getPort();
        api.createContext("/repos/apache/ignite/actions/jobs/7/logs", ex -> {
            authorization.put("api", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getResponseHeaders().add("Location", base + "/blob/7.txt?sig=abc");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        api.createContext("/blob/7.txt", ex -> {
            authorization.put("blob", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "line one\nline two\n".getBytes(UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        api.start();
        try {
            GithubClient service = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300, null, base),
                mapper, new Metrics(mapper));

            assertThat(service.jobLog(7, lines -> lines.count())).hasValue(2L);
            assertThat(authorization).containsEntry("api", "Bearer app-token").containsEntry("blob", "null");
        }
        finally {
            api.stop(0);
        }
    }
}
