package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Post to JIRA posts with the JIRA token alice stored on the server. A copy of her cookie whose TeamCity token she
 * revoked in ci2 a moment ago, or a form on a page of another site that her browser sends the cookie with, could post
 * visas in her name: the login guard learns of a revoked token only once a call under it fails, and the visa calls no
 * TeamCity. Both visa posts, Ignite's and the Python client's, ask TeamCity whether the token works first, and come
 * only from the checker's own page. The visa line the page polls asks TeamCity nothing.
 */
class PostVisaGuardsTest {
    private static final String USER = "alice";

    private static final List<String> POSTS = List.of("/api/visa?pr=13709&build=9425992&issue=IGNITE-29100",
        "/api/client/visa?pr=69&build=9424200&issue=IGNITE-29117");

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Logins logins = new Logins(mapper, users);

    private final Warmer warmer = new Warmer(mock(BlockerAnalyzer.class), github,
        new WarmProperties(false, null, null, null), mock(ExecutorService.class));

    private final AuthInterceptor auth = new AuthInterceptor(codec, warmer, users, logins, standing);

    private final MergedVerdicts merged = mock(MergedVerdicts.class);

    private final MockMvc mvc = guardedAsTheServiceGuards(MockMvcBuilders.standaloneSetup(
            new PostVisaController(standing, mock(BlockerAnalyzer.class), merged, tc,
                new PrTitleController(github, merged), mock(ClientVerdicts.class), mock(PythonClientRuns.class),
                mock(PrCommands.class), mock(ClientPrsController.class)),
            new JiraController(mock(JiraClient.class), tc, standing, merged, users)))
        .addFilters(new SameOriginFilter())
        .build();

    @Test
    void aVisaOnATokenTeamCityRefusesIsRefused() throws Exception {
        when(tc.tokenRejected(anyString())).thenReturn(true);

        for (int i = 0; i < POSTS.size(); i++) {
            String token = "revoked-tc-" + i;
            MockHttpServletResponse res = send(post(POSTS.get(i)), token);

            assertThat(res.getStatus()).as(POSTS.get(i)).isEqualTo(401);
            assertThat(res.getContentAsString()).as(POSTS.get(i)).contains("\"tokenRejected\":true");
            assertThat(warmer.tokenRevoked(token)).as("the guard refuses the token's sessions from now on").isTrue();
        }
        verify(standing, never()).visaButton(any(), any(), anyInt(), anyLong());
        verify(standing, never()).postVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any());
        verify(standing, never()).postClientVisaFromPage(any(), any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void aVisaFromAPageOfAnotherSiteIsRefused() throws Exception {
        for (String path : POSTS) {
            MockHttpServletResponse res = send(post(path).header("Sec-Fetch-Site", "cross-site"), "live-tc");

            assertThat(res.getStatus()).as(path).isEqualTo(403);
            assertThat(res.getContentAsString()).isEqualTo(SameOriginFilter.REFUSED);
        }
        verifyNoInteractions(standing);
        verify(tc, never()).tokenRejected(anyString());
    }

    @Test
    void theVisaLineThePagePollsAsksTeamCityNothingOfTheToken() throws Exception {
        when(standing.visaLine(anyString(), anyInt(), anyLong(), any(), anyBoolean())).thenReturn(
            new StandingVisas.VisaLine(StandingVisas.VisaLine.ON, "bob", false, null, null, false));

        assertThat(send(get("/api/visa?pr=13709&build=9425992"), "live-tc").getStatus()).isEqualTo(200);

        verify(tc, never()).tokenRejected(anyString());
    }

    /** The login guard and the live token check on the paths and in the order the service registers them. */
    private StandaloneMockMvcBuilder guardedAsTheServiceGuards(StandaloneMockMvcBuilder builder) {
        Registry registry = new Registry();
        new WebConfig(auth, new LiveTokenGuard(tc, warmer)).addInterceptors(registry);
        for (Object added : registry.interceptors()) {
            MappedInterceptor mapped = (MappedInterceptor)added;
            builder.addMappedInterceptors(mapped.getIncludePathPatterns(), mapped.getInterceptor());
        }

        return builder;
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String token) throws Exception {
        Cookie cookie = new Cookie(AuthInterceptor.COOKIE, codec.encode(new SessionCodec.Session(USER, token)));

        return mvc.perform(req.cookie(cookie)).andReturn().getResponse();
    }

    /** The registry {@link WebConfig} fills, with what it holds in view. */
    private static final class Registry extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
