package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A TeamCity token that expired stayed in charge of its owner's options: logging in again with a
 * fresh one renewed the cookie but not the token the checker stored, so the very fix the user was
 * told to do changed nothing.
 */
class TcTokenRenewalTest {
    private static final String USER = "nsamelchev";

    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final SessionProperties session = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(session, mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, mock(GithubClient.class),
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void refused() {
        standing.change(USER, "expired-tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        standing.markTcRejected(USER);
    }

    @Test
    void aLoginWithAFreshTokenResumesTheOptions() {
        when(tc.currentUsername("fresh-tc")).thenReturn(Optional.of(USER));
        LoginController login = new LoginController(tc, codec, session, mock(Warmer.class), mock(UserDirectory.class),
            new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper), standing);

        assertThat(login.login(new LoginController.LoginRequest("fresh-tc"), new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(200);

        assertThat(standing.tcTokenRejected(USER)).isFalse();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("fresh-tc"));
    }

    /** Logged in with a fresh token in another browser before TeamCity refused the stored one. */
    @Test
    void aRequestOfASessionWithAnotherTokenResumesThem() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(USER, "fresh-tc")));
        AuthInterceptor auth = new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class), standing);

        assertThat(auth.preHandle(req, new MockHttpServletResponse(), new Object())).isTrue();

        assertThat(standing.tcTokenRejected(USER)).isFalse();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("fresh-tc"));
    }
}
