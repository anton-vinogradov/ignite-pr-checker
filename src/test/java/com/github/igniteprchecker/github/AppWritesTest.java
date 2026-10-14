package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Nothing in the checker heard GitHub's 403 and 429 of its secondary rate limit: writing on through them gets the
 * account banned, and a ban stops every user's comments at once, since one account writes them all. Any TeamCity user
 * who runs RunAll on someone's PR makes that account write there, and nothing capped how much.
 */
class AppWritesTest {
    private static final long NOW = 1_800_000_000_000L;

    private static final String COMMENTS = "https://api.github.com/repos/apache/ignite/issues/13800/comments";

    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aWriteGithubMadeWaitIsDeferredAndTheNextOnesDoNotReachGithubMeanwhile() {
        GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300), mapper,
            new Metrics(mapper), http.build());
        server.expect(once(), requestTo(COMMENTS)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "120"));

        assertThatThrownBy(() -> github.appComment(13800, "alice", "verdict"))
            .isInstanceOf(GithubClient.WriteDeferred.class);
        assertThatThrownBy(() -> github.appReact(13800, "alice", 77L, "rocket"))
            .isInstanceOf(GithubClient.WriteDeferred.class);

        server.verify();
        assertThat(github.canWrite()).isFalse();
        assertThat(github.writeState().pausedUntil()).isGreaterThan(System.currentTimeMillis() + 100_000);
        assertThat(github.appAccount().refused()).as("a rate limit says nothing of the token").isFalse();
    }

    @Test
    void retryAfterIsHeardFirstThenTheResetOfAnExhaustedLimit() {
        assertThat(AppWrites.waitUntil(limited(403, "Retry-After", "30"), NOW, 60_000)).isEqualTo(NOW + 30_000);
        assertThat(AppWrites.waitUntil(limited(403, "X-RateLimit-Remaining", "0", "X-RateLimit-Reset",
            String.valueOf(NOW / 1000 + 600)), NOW, 60_000)).isEqualTo(NOW + 600_000);
        assertThat(AppWrites.waitUntil(limited(429), NOW, 60_000)).isEqualTo(NOW + 60_000);
    }

    /** "You have exceeded a secondary rate limit": a 403 with no header that says how long. */
    @Test
    void aSecondaryLimitWithNoWordOnHowLongWaitsAMinuteDoubledEachTimeInARow() {
        AppWrites writes = new AppWrites();
        HttpClientErrorException secondary = HttpClientErrorException.create(HttpStatusCode.valueOf(403), "Forbidden",
            HttpHeaders.EMPTY, "{\"message\":\"You have exceeded a secondary rate limit.\"}"
                .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(writes.limited(secondary, NOW)).isTrue();
        assertThat(writes.state(NOW).pausedUntil()).isEqualTo(NOW + 60_000);
        assertThat(writes.limited(secondary, NOW + 60_000)).isTrue();
        assertThat(writes.state(NOW + 60_000).pausedUntil()).isEqualTo(NOW + 180_000);

        writes.worked();
        assertThat(writes.limited(secondary, NOW + 200_000)).isTrue();
        assertThat(writes.state(NOW + 200_000).pausedUntil()).isEqualTo(NOW + 260_000);
    }

    /** A 403 that is no limit: the account is blocked in the repo, or the PR locked. The status page says so. */
    @Test
    void aRefusalThatIsNoLimitIsKeptUntilAWriteGoesIn() {
        AppWrites writes = new AppWrites();
        HttpClientErrorException blocked = HttpClientErrorException.create(HttpStatusCode.valueOf(403), "Forbidden",
            HttpHeaders.EMPTY, "{\"message\":\"Unable to create comment because issue is locked.\"}"
                .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(writes.limited(blocked, NOW)).isFalse();

        assertThat(writes.state(NOW).pausedUntil()).isZero();
        assertThat(writes.state(NOW).refused()).contains("GitHub answered 403 to a write", "issue is locked");
        writes.worked();
        assertThat(writes.state(NOW).refused()).isNull();
    }

    @Test
    void theWritesToOnePrAndForOneUserAreCappedAnHour() {
        AppWrites writes = new AppWrites();
        for (int i = 0; i < AppWrites.PER_PR_HOUR; i++) {
            writes.admit(13800, "user" + i, NOW);
            writes.sent(13800, "user" + i, NOW);
        }

        assertThatThrownBy(() -> writes.admit(13800, "someone", NOW)).isInstanceOf(GithubClient.WriteDeferred.class);
        writes.admit(13801, "someone", NOW);
        assertThat(writes.state(NOW).capped()).containsExactly("PR 13800");
        writes.admit(13800, "someone", NOW + 3600_001L);

        for (int i = 0; i < AppWrites.PER_USER_HOUR; i++) {
            writes.admit(20000 + i, "bob", NOW);
            writes.sent(20000 + i, "bob", NOW);
        }
        assertThatThrownBy(() -> writes.admit(30000, "bob", NOW)).isInstanceOf(GithubClient.WriteDeferred.class);
        assertThat(writes.state(NOW).capped()).containsExactly("PR 13800", "user bob");
        assertThat(new GithubClient.WriteState(0, null, 0, writes.state(NOW).capped()).anonymous().capped())
            .as("whose writes is not for anyone to see").containsExactly("PR 13800", "a user");
    }

    @Test
    void aWriteThatWorksClearsTheRefusalAndCounts() {
        GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300), mapper,
            new Metrics(mapper), http.build());
        server.expect(requestTo(COMMENTS)).andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"message\":\"blocked\"}")
            .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(COMMENTS)).andRespond(withSuccess("{\"id\":77,\"html_url\":\"https://github.com/x\"}",
            MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> github.appComment(13800, "alice", "verdict"))
            .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThat(github.writeState().refused()).contains("blocked");
        assertThat(github.appComment(13800, "alice", "verdict").id()).isEqualTo(77L);

        assertThat(github.writeState().refused()).isNull();
        assertThat(github.canWrite()).isTrue();
    }

    @Test
    void withoutATokenNothingIsWrittenAndNothingCanBe() {
        GithubClient github = new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper,
            new Metrics(mapper), http.build());

        assertThat(github.appComment(13800, "alice", "verdict")).isNull();
        assertThat(github.appDelete(13800, "alice", 77L)).isFalse();
        assertThat(github.writes()).isFalse();
        assertThat(github.canWrite()).isFalse();
        server.verify();
    }

    private static HttpClientErrorException limited(int status, String... headers) {
        HttpHeaders h = new HttpHeaders();
        for (int i = 0; i < headers.length; i += 2)
            h.add(headers[i], headers[i + 1]);

        return HttpClientErrorException.create(HttpStatusCode.valueOf(status), "Forbidden", h, new byte[0],
            StandardCharsets.UTF_8);
    }
}
