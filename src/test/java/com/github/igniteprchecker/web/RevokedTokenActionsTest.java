package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import com.github.igniteprchecker.update.UpdateService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A copy of alice's cookie, or her own session right after she revoked its token in ci2, could link a GitHub account,
 * switch her options, log her out everywhere, restart, update and flush the service: the login guard knew of a revoked
 * token only once a call under it failed, and only the warmer recorded such a failure. A GitHub account linked that way
 * stayed linked after her logout and ran /run-all under her TeamCity account.
 */
class RevokedTokenActionsTest {
    private static final String USER = "alice";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final UpdateService update = mock(UpdateService.class);

    private final AnalysisCache cache = mock(AnalysisCache.class);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Warmer warmer = new Warmer(mock(BlockerAnalyzer.class), github,
        new WarmProperties(false, null, null, null), mock(ExecutorService.class));

    private final AdminActions admin = new AdminActions(new AdminProperties(null), mapper);

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, standing);

    private final LoginController login = new LoginController(tc, codec, props, warmer, users, new LoginThrottle(),
        admin, standing);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new GithubLinkController(github, standing, auth),
            new JiraController(mock(JiraClient.class), tc, mock(BlockerAnalyzer.class), codec, mock(VisaService.class),
                mock(VisaSubscriptions.class), standing, mock(PendingCommits.class), false),
            new CacheController(cache, warmer, admin),
            new UpdateController(update, admin))
        .addMappedInterceptors(new String[] {"/api/**"}, auth)
        .addMappedInterceptors(WebConfig.LIVE_TOKEN_ACTIONS, new LiveTokenGuard(tc, warmer))
        .build();

    @Test
    void anActionOnATokenTeamCityRefusesIsRefused() throws Exception {
        when(tc.tokenRejected(anyString())).thenReturn(true);
        List<String> actions = List.of("/api/github/link", "/api/github/unlink", "/api/auto-visa-all?visa=true",
            "/api/restart", "/api/update", "/api/flush-caches");

        for (int i = 0; i < actions.size(); i++) {
            String token = "revoked-tc-" + i;
            MockHttpServletResponse res = send(post(actions.get(i)), token);

            assertThat(res.getStatus()).as(actions.get(i)).isEqualTo(401);
            assertThat(res.getContentAsString()).as(actions.get(i)).contains(ApiExceptionHandler.TOKEN_REJECTED)
                .contains("\"tokenRejected\":true");
            assertThat(warmer.tokenRevoked(token)).as("the guard refuses the token's sessions from now on").isTrue();
            assertThat(send(get("/api/auto-visa-all"), token).getContentAsString()).contains(AuthInterceptor.REVOKED);
        }
        verify(github, never()).authorizeUri(anyString());
        verify(standing, never()).unlinkGhLogin(anyString());
        verify(standing, never()).change(anyString(), anyString(), any(), any(), any(), any());
        verify(update, never()).restart();
        verify(update, never()).performUpdate();
        verifyNoInteractions(cache);
        assertThat(admin.lastUses()).isEmpty();
    }

    /** One call to TeamCity per action; a token it accepts goes through, and reading the settings asks nothing. */
    @Test
    void aLiveTokenGoesThrough() throws Exception {
        assertThat(send(get("/api/auto-visa-all"), "live-tc").getStatus()).isEqualTo(200);
        verifyNoInteractions(tc);

        assertThat(send(post("/api/github/unlink"), "live-tc").getStatus()).isEqualTo(200);
        assertThat(send(post("/api/flush-caches"), "live-tc").getStatus()).isEqualTo(200);

        verify(tc, times(2)).tokenRejected("live-tc");
        verify(standing).unlinkGhLogin(USER);
        verify(cache).clear();
    }

    /** A copy of the cookie whose token alice revoked could log her out of the sessions on her new token. */
    @Test
    void aLogoutEverywhereOnARefusedTokenEndsNoOtherSession() throws Exception {
        when(tc.tokenRejected("revoked-tc")).thenReturn(true);
        String other = codec.encode(new SessionCodec.Session(USER, "new-tc", null), System.currentTimeMillis() - 1000);

        var out = login.logout(true, codec.encode(new SessionCodec.Session(USER, "revoked-tc", null)));

        assertThat(out.getStatusCode().value()).isEqualTo(401);
        assertThat(out.getBody()).containsEntry("tokenRejected", true);
        assertThat(users.ended(codec.decode(other).orElseThrow())).isFalse();
        assertThat(warmer.tokenRevoked("revoked-tc")).isTrue();
    }

    /** A 401 a user's own call got, confirmed by TeamCity, answered that call only: the next one tried again. */
    @Test
    void aConfirmedRefusalOfAUsersCallRefusesTheirSession() throws Exception {
        when(tc.tokenRejected("revoked-tc")).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthInterceptor.TOKEN_ATTR, "revoked-tc");

        var res = new ApiExceptionHandler(tc, warmer).teamCityRefused(new TcResponseException(
            HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null)), req);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody()).isEqualTo(Map.of("error", ApiExceptionHandler.TOKEN_REJECTED, "tokenRejected", true));
        assertThat(send(get("/api/auto-visa-all"), "revoked-tc").getStatus()).isEqualTo(401);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String token) throws Exception {
        Cookie cookie = new Cookie(AuthInterceptor.COOKIE, codec.encode(new SessionCodec.Session(USER, token, null)));

        return mvc.perform(req.cookie(cookie)).andReturn().getResponse();
    }
}
