package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The command poll read one page of 100 comments. After a GitHub outage the window holds more than
 * that, and a /run-all on the second page was never seen.
 */
class CommentPagesTest {
    private static final String SINCE = "2026-10-07T10:00:00Z";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
        new Metrics(mapper), http.build());

    @Test
    void aFullPageIsFollowedByTheNext() {
        server.expect(requestTo(page(1))).andRespond(withSuccess(comments(1, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(page(2))).andRespond(withSuccess(comments(101, 101), MediaType.APPLICATION_JSON));

        List<GithubClient.IssueComment> read = github.recentIssueComments(SINCE);

        assertThat(read).hasSize(101).last().extracting(GithubClient.IssueComment::id).isEqualTo(101L);
        server.verify();
    }

    @Test
    void aShortPageIsTheLast() {
        server.expect(requestTo(page(1))).andRespond(withSuccess(comments(1, 3), MediaType.APPLICATION_JSON));

        assertThat(github.recentIssueComments(SINCE)).hasSize(3);
        server.verify();
    }

    private static String page(int n) {
        return "https://api.github.com/repos/apache/ignite/issues/comments?since=" + SINCE
            + "&sort=updated&direction=asc&per_page=100&page=" + n;
    }

    private static String comments(long from, long to) {
        return LongStream.rangeClosed(from, to)
            .mapToObj(id -> "{\"id\":" + id + ",\"body\":\"lgtm\",\"html_url\":\"https://github.com/apache/ignite/pull/"
                + "13335#issuecomment-" + id + "\",\"created_at\":\"" + SINCE + "\",\"user\":{\"login\":\"someone\"}}")
            .collect(Collectors.joining(",", "[", "]"));
    }
}
