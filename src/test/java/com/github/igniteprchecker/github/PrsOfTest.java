package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * A user asked the PR list to show their own PRs, and those they review or commented on. The list held the 50 most
 * recently updated PRs of everyone, so a PR of theirs nobody touched for a while was not in it. GitHub's search finds
 * all of them: four searches in one GraphQL call under the checker's token, each PR with its head, so its badge can
 * tell a run of its current code.
 */
class PrsOfTest {
    private static final String HEAD = "5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final Metrics metrics = new Metrics(mapper);

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300),
        mapper, metrics, http.build());

    @Test
    void oneCallFindsTheUsersPrsAndTheOnesTheyTakePartIn() {
        server.expect(requestTo("https://api.github.com/graphql"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer app-token"))
            .andExpect(content().json("""
                {"variables": {
                  "mine": "repo:apache/ignite is:pr is:open sort:updated-desc author:alice-gh",
                  "requested": "repo:apache/ignite is:pr is:open sort:updated-desc review-requested:alice-gh -author:alice-gh",
                  "reviewed": "repo:apache/ignite is:pr is:open sort:updated-desc reviewed-by:alice-gh -author:alice-gh",
                  "commented": "repo:apache/ignite is:pr is:open sort:updated-desc commenter:alice-gh -author:alice-gh"}}
                """))
            .andRespond(withSuccess("""
                {"data": {
                  "mine": {"nodes": [
                    {"number": 13575, "title": "IGNITE-29049 Move MDC to JUnit",
                     "url": "https://github.com/apache/ignite/pull/13575", "headRefOid": "%s",
                     "updatedAt": "2026-10-19T10:00:00Z"},
                    {"number": 12950, "title": "IGNITE-28000 Old but mine",
                     "url": "https://github.com/apache/ignite/pull/12950", "headRefOid": "0a1b2c3d",
                     "updatedAt": "2026-03-01T10:00:00Z"}]},
                  "requested": {"nodes": [
                    {"number": 13461, "title": "IGNITE-28972 Check marshalled kind",
                     "url": "https://github.com/apache/ignite/pull/13461", "headRefOid": "1111111",
                     "updatedAt": "2026-10-10T10:00:00Z"}]},
                  "reviewed": {"nodes": [
                    {"number": 13600, "title": "Fix a typo", "url": "https://github.com/apache/ignite/pull/13600",
                     "headRefOid": "2222222", "updatedAt": "2026-10-18T10:00:00Z"},
                    {}]},
                  "commented": {"nodes": [
                    {"number": 13461, "title": "IGNITE-28972 Check marshalled kind",
                     "url": "https://github.com/apache/ignite/pull/13461", "headRefOid": "1111111",
                     "updatedAt": "2026-10-10T10:00:00Z"}]}}}
                """.formatted(HEAD), MediaType.APPLICATION_JSON));

        GithubClient.PrsOf found = github.prsOf("alice-gh");

        assertThat(found.mine()).extracting(PrSummary::number, PrSummary::title, PrSummary::url, PrSummary::headSha)
            .containsExactly(
                tuple(13575, "IGNITE-29049 Move MDC to JUnit", "https://github.com/apache/ignite/pull/13575", HEAD),
                tuple(12950, "IGNITE-28000 Old but mine", "https://github.com/apache/ignite/pull/12950", "0a1b2c3d"));
        assertThat(found.involved()).as("once each, the most recently updated first, an issue left out")
            .extracting(PrSummary::number, PrSummary::headSha)
            .containsExactly(tuple(13600, "2222222"), tuple(13461, "1111111"));
        server.verify();
    }

    /**
     * GitHub answers a GraphQL call it could not run with 200 and errors in place of the data. The status page counts
     * the search as failed: the user got no list.
     */
    @Test
    void aSearchGithubLeftOutFailsTheCall() {
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess("""
            {"data": null, "errors": [{"type": "RATE_LIMITED", "message": "API rate limit exceeded"}]}
            """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> github.prsOf("alice-gh")).hasMessageContaining("RATE_LIMITED");
        assertThat(metrics.github().byCategory().get("prSearch"))
            .extracting(Metrics.Stats::ok, Metrics.Stats::fail).containsExactly(0L, 1L);
    }

    /**
     * GitHub ran three searches and timed out on the fourth. Involved without it would lose every PR the user only
     * commented on, so the call fails and the last good answer stays.
     */
    @Test
    void oneSearchLeftOutFailsTheCall() {
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess("""
            {"data": {"mine": {"nodes": []}, "requested": {"nodes": []}, "reviewed": {"nodes": []}, "commented": null},
             "errors": [{"type": "TIMEOUT", "path": ["commented"], "message": "Timeout on search"}]}
            """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> github.prsOf("alice-gh")).hasMessageContaining("TIMEOUT");
    }

    /** A login is put into the search as it is, so one GitHub would not allow could change what is searched. */
    @Test
    void aLoginGithubWouldNotAllowSearchesNothing() {
        GithubClient.PrsOf found = github.prsOf("alice repo:other/repo");

        assertThat(found.mine()).isEmpty();
        assertThat(found.involved()).isEmpty();
        server.verify();
    }
}
