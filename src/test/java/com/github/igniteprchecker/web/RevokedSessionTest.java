package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The session cookie lives ten years and is never checked again, so after TeamCity revoked a user's token the
 * old session kept restarting, flushing and listing users. TeamCity's 401 to that token in the background warm
 * now ends such sessions until a fresh login; a 403, which the ci2 WAF also gives valid tokens, does not.
 */
class RevokedSessionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(true, "test-secret"), mapper);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final Warmer warmer;

    private final AuthInterceptor auth;

    RevokedSessionTest() {
        GithubClient github = mock(GithubClient.class);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13655, "IGNITE-1 x", "u", null, null, null)));
        warmer = new Warmer(analyzer, github, new WarmProperties(true, 50, 10, 60), pool);
        auth = new AuthInterceptor(codec, warmer, new UserDirectory(mapper), mock(StandingVisas.class));
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private MockHttpServletRequest session() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode("avinogradov", "tok")));

        return req;
    }

    private void teamCityAnswers(HttpStatus status) {
        when(analyzer.warm(anyString(), anyInt()))
            .thenThrow(HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], UTF_8));
    }

    /** The login that ends the refusal starts another warm cycle, which must not meet the old 401 again. */
    private void loginWithTheTokenReissued() {
        doReturn(false).when(analyzer).warm(anyString(), anyInt());
        warmer.offerVerifiedToken("tok");
    }

    private void warmCycleRuns() throws Exception {
        assertThat(auth.preHandle(session(), new MockHttpServletResponse(), null)).isTrue(); // donates the token

        await().atMost(Duration.ofSeconds(5)).until(() -> warmer.cyclesCompleted() >= 1);
    }

    @Test
    void a401EndsTheSessionUntilTheNextLogin() throws Exception {
        teamCityAnswers(HttpStatus.UNAUTHORIZED);
        warmCycleRuns();

        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(auth.preHandle(session(), res, null)).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("TeamCity rejected your token");
        assertThat(auth.signedIn(session())).isEmpty();

        loginWithTheTokenReissued();

        assertThat(auth.preHandle(session(), new MockHttpServletResponse(), null)).isTrue();
    }

    /** The page shows why only when /api/me refuses: let in there, the user met a bare login form on the next call. */
    @Test
    void theRevokedSessionIsToldWhyOnItsFirstCall() throws Exception {
        LoginController login = new LoginController(null, codec, null, warmer, new UserDirectory(mapper), null,
            new AdminActions(new AdminProperties(null), mapper), mock(StandingVisas.class));
        teamCityAnswers(HttpStatus.UNAUTHORIZED);
        warmCycleRuns();

        ResponseEntity<?> me = login.me(codec.encode("avinogradov", "tok"));

        assertThat(me.getStatusCode().value()).isEqualTo(401);
        assertThat(me.getBody()).isEqualTo(Map.of("error", "TeamCity rejected your token — log in again"));

        loginWithTheTokenReissued();

        assertThat(login.me(codec.encode("avinogradov", "tok")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void a403LeavesTheSessionAlone() throws Exception {
        teamCityAnswers(HttpStatus.FORBIDDEN);
        warmCycleRuns();

        assertThat(auth.preHandle(session(), new MockHttpServletResponse(), null)).isTrue();
        assertThat(auth.signedIn(session())).isPresent();
    }
}
