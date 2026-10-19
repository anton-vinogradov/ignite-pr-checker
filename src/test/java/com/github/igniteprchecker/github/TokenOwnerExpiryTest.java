package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * ⚙ says how long the user's GitHub token for commits has left. GitHub names the expiry of a token in a header of
 * each answer to it, and sends no such header for a token that never expires.
 */
class TokenOwnerExpiryTest {
    private static final String USER = "{\"login\":\"alice-gh\",\"id\":4242}";

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300),
        mapper, new Metrics(mapper), http.build());

    @Test
    void theOwnerOfATokenComesWithItsExpiry() {
        HttpHeaders expiring = new HttpHeaders();
        expiring.add(AppAccountChecks.EXPIRATION, "2026-12-01 15:00:00 +0300");
        server.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization", "Bearer gh-pat"))
            .andRespond(withSuccess(USER, MediaType.APPLICATION_JSON).headers(expiring));
        server.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess(USER, MediaType.APPLICATION_JSON));

        GithubClient.TokenOwner owner = github.tokenOwner("gh-pat").orElseThrow();
        GithubClient.TokenOwner forever = github.tokenOwner("gh-forever").orElseThrow();

        assertThat(owner.expiration()).isEqualTo("2026-12-01 15:00:00 +0300");
        assertThat(owner.expiresAt()).contains(Instant.parse("2026-12-01T12:00:00Z"));
        assertThat(forever.expiration()).isNull();
        assertThat(forever.expiresAt()).isEmpty();
        assertThat(new GithubClient.TokenOwner(GhUsers.of("alice-gh"), null, "soon").expiresAt()).isEmpty();
    }
}
