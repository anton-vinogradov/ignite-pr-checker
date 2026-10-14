package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Onboarding replies, reactions and the narration of users without a token of their own were written as whoever
 * GITHUB_TOKEN belonged to — on prod the owner's personal account, which can push to apache/ignite — and the docs
 * called it an optional read token. The checker now asks GitHub at startup whose token it is and what it may do.
 */
class AppAccountTest {
    private final RestClient.Builder http = RestClient.builder();

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theAccountTheCheckerWritesAsIsNamed() {
        server.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization", "Bearer app-token"))
            .andRespond(withSuccess("{\"login\":\"ignite-pr-checker-bot\",\"id\":234567}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"admin\":false,\"push\":false,\"pull\":true}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/anton-vinogradov/ignite-pr-checker"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        GithubClient github = client("app-token");

        github.checkAppAccount();

        assertThat(github.appAccount()).extracting(GithubClient.AppAccount::state, GithubClient.AppAccount::login,
            GithubClient.AppAccount::canPush, GithubClient.AppAccount::id)
            .containsExactly("ok", "ignite-pr-checker-bot", false, 234567L);
    }

    @Test
    void anAccountThatCanPushToTheRepoIsFlagged() {
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"anton-vinogradov\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/anton-vinogradov/ignite-pr-checker"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":true}}", MediaType.APPLICATION_JSON));
        GithubClient github = client("app-token");

        github.checkAppAccount();

        assertThat(github.appAccount().canPush()).isTrue();
    }

    /** GitHub tells of the token in the headers of its answer: its scopes and, for one that expires, when. */
    @Test
    void theTokensScopesAndExpiryAreReadOffGithubsAnswer() {
        String expires = LocalDateTime.now(ZoneOffset.UTC).plusDays(3).plusHours(1)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC";
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-OAuth-Scopes", "public_repo, workflow");
        headers.add("github-authentication-token-expiration", expires);
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"ignite-pr-checker\",\"id\":234567}", MediaType.APPLICATION_JSON)
                .headers(headers));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/anton-vinogradov/ignite-pr-checker"))
            .andRespond(withSuccess("{\"name\":\"ignite-pr-checker\"}", MediaType.APPLICATION_JSON));
        GithubClient github = client("ghp_app");

        github.checkAppAccount();

        assertThat(github.appAccount().checks()).extracting(GithubClient.AppAccount.Check::level)
            .containsExactly("warn", "warn", "ok", "unknown");
        assertThat(github.appAccount().checks().get(1).text()).startsWith("GITHUB_TOKEN expires on "
            + expires.substring(0, 10) + ", in 3 days");
    }

    /** The check runs again every 6 hours: one that GitHub failed must not make the known account unknown. */
    @Test
    void aRecheckGithubFailedKeepsWhatItSaidLast() {
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"ignite-pr-checker\",\"id\":234567}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/anton-vinogradov/ignite-pr-checker"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        GithubClient github = client("app-token");
        github.checkAppAccount();
        GithubClient.AppAccount known = github.appAccount();

        github.checkAppAccount();

        assertThat(github.appAccount()).isEqualTo(known);
        assertThat(github.isAppAccount(new GithubClient.GhUser("ignite-pr-checker", 234567))).isTrue();
    }

    /** A 403 on the recheck is GitHub's rate limit, which says nothing of the token: only a 401 refuses it. */
    @Test
    void aRateLimitOnTheRecheckIsNoRefusal() {
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"ignite-pr-checker\",\"id\":234567}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/anton-vinogradov/ignite-pr-checker"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":false}}", MediaType.APPLICATION_JSON));
        HttpHeaders limited = new HttpHeaders();
        limited.add("x-ratelimit-remaining", "0");
        server.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.FORBIDDEN)
            .headers(limited).contentType(MediaType.APPLICATION_JSON)
            .body("{\"message\":\"API rate limit exceeded for user ID 234567.\"}"));
        GithubClient github = client("app-token");
        github.checkAppAccount();
        GithubClient.AppAccount known = github.appAccount();

        github.checkAppAccount();

        assertThat(github.appAccount()).isEqualTo(known);
        assertThat(github.appAccount().state()).isEqualTo("ok");
    }

    @Test
    void aTokenGithubRefusesIsSaid() {
        server.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        GithubClient github = client("revoked");

        github.checkAppAccount();

        assertThat(github.appAccount().state()).isEqualTo("refused");
    }

    @Test
    void withoutATokenThereIsNoAccountAndNothingIsWritten() {
        GithubClient github = client(null);

        github.checkAppAccount();
        github.appEdit(13800, "author", 77L, "@Author 🏁 _Run finished._");

        assertThat(github.appAccount().state()).isEqualTo("none");
        assertThat(github.writes()).isFalse();
        server.verify();
    }

    private GithubClient client(String token) {
        return new GithubClient(new GithubProperties("apache/ignite", token, 300), mapper, new Metrics(mapper),
            http.build());
    }
}
