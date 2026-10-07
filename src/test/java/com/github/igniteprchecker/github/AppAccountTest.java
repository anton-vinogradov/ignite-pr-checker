package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import org.junit.jupiter.api.Test;
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
            .andRespond(withSuccess("{\"login\":\"ignite-pr-checker-bot\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"admin\":false,\"push\":false,\"pull\":true}}",
                MediaType.APPLICATION_JSON));
        GithubClient github = client("app-token");

        github.checkAppAccount();

        assertThat(github.appAccount()).isEqualTo(new GithubClient.AppAccount("ok", "ignite-pr-checker-bot", false));
    }

    @Test
    void anAccountThatCanPushToTheRepoIsFlagged() {
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"anton-vinogradov\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/apache/ignite"))
            .andRespond(withSuccess("{\"permissions\":{\"push\":true}}", MediaType.APPLICATION_JSON));
        GithubClient github = client("app-token");

        github.checkAppAccount();

        assertThat(github.appAccount().canPush()).isTrue();
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
        github.updatePrCommentAsApp(77L, "@Author 🏁 _Run finished._");

        assertThat(github.appAccount().state()).isEqualTo("none");
        server.verify();
    }

    private GithubClient client(String token) {
        return new GithubClient(new GithubProperties("apache/ignite", token, 300), mapper, new Metrics(mapper),
            http.build());
    }
}
