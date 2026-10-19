package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.LogCapture;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A GitHub token sent without quotes in the body of /api/auto-visa-all went into the log whole, in Jackson's
 * "Unrecognized token 'ghp_…'", and from there into problems.json and the status page; a JIRA token went in up to its
 * first "+" or "/". A token with a line break, sent by a script to /api/login, reached the JDK's HTTP client, whose
 * error names the refused header, "Bearer <token>". With Spring's web logging at DEBUG, every login body went into the
 * log, and at TRACE every token a handler gets.
 */
class TokensOutOfLogsTest {
    private static final String USER = "alice";

    private static final String GH = "ghp_FAKEfakeFAKEfake0123456789abcdefABCD";

    private static final String FINE_GRAINED = "github_pat_11FAKE0fake0FAKE_fakeFAKEfake0123456789";

    private static final String JIRA = "FAKEjiraPAT0123456789abcdefghijklmnopqrstuv";

    private static final String TC = "eyJ0eXAiOiAiVENWMiJ9.FAKEtcBODY0123456789.FAKEtcSIGN0123456789";

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final TcClient tc = mock(TcClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new LoginController(tc, codec, props, mock(Warmer.class), new UserDirectory(mapper), new LoginThrottle(),
                new AdminActions(new AdminProperties(null), mapper), standing),
            new JiraController(jira, tc, standing, mock(MergedVerdicts.class), new UserDirectory(mapper)))
        .setControllerAdvice(new ApiExceptionHandler(tc, mock(Warmer.class)), new TokenFormat())
        .build();

    @Test
    void aTokenTypedWithoutQuotesStaysOutOfTheLog() throws Exception {
        try (LogCapture log = new LogCapture(Map.of("org.springframework", Level.DEBUG))) {
            MockHttpServletResponse login = send(post("/api/login"), "{\"token\": " + TC + "}");
            MockHttpServletResponse jiraToken = send(signedIn(post("/api/auto-visa-all").param("visa", "true")),
                "{\"jiraToken\": " + JIRA + "}");
            MockHttpServletResponse classic = send(signedIn(post("/api/auto-visa-all").param("style", "true")),
                "{\"githubToken\": " + GH + "}");
            MockHttpServletResponse fineGrained = send(signedIn(post("/api/auto-visa-all").param("style", "true")),
                "{\n  \"githubToken\": " + FINE_GRAINED + "\n}");

            for (MockHttpServletResponse res : new MockHttpServletResponse[] {login, jiraToken, classic, fineGrained}) {
                assertThat(res.getStatus()).isEqualTo(400);
                assertThat(res.getContentAsString()).contains(ApiExceptionHandler.BAD_JSON);
            }
            assertThat(log.text()).contains("Unreadable body of POST /api/login at line 1, column ",
                "Unreadable body of POST /api/auto-visa-all at line 2, column ");
            assertNoToken(log.text());
        }
        verifyNoInteractions(tc, jira, github, standing);
    }

    @Test
    void aTokenWithALineBreakIsRefusedBeforeAnyServiceSeesIt() throws Exception {
        String broken = TC.replace(".FAKEtcSIGN", "\\n.FAKEtcSIGN");

        try (LogCapture log = new LogCapture(Map.of("org.springframework", Level.DEBUG))) {
            MockHttpServletResponse login = send(post("/api/login"), "{\"token\":\"" + broken + "\"}");
            MockHttpServletResponse jiraToken = send(signedIn(post("/api/auto-visa-all").param("visa", "true")),
                "{\"jiraToken\":\"" + JIRA + "\\r\\nX-Other: 1\"}");
            MockHttpServletResponse option = send(signedIn(post("/api/auto-visa-all").param("style", "true")),
                "{\"githubToken\":\"" + GH + " " + GH + "\"}");
            MockHttpServletResponse both = send(signedIn(post("/api/auto-visa-all").param("visa", "true")),
                "{\"githubToken\":\"" + GH + "\",\"jiraToken\":\"" + JIRA + "\\nX-Other: 1\"}");

            for (MockHttpServletResponse res : new MockHttpServletResponse[] {login, jiraToken, option, both}) {
                assertThat(res.getStatus()).isEqualTo(400);
                assertThat(res.getContentAsString()).contains("This is not a token");
                assertNoToken(res.getContentAsString());
            }
            assertNoToken(log.text());
        }
        verifyNoInteractions(tc, jira, github, standing);
    }

    /** A body of JSON {@code null} failed the token check with a 500 and an ERROR, which turned the health amber. */
    @Test
    void aNullBodyIsAnsweredAsAnEmptyOne() throws Exception {
        MockHttpServletResponse login = send(post("/api/login"), "null");
        MockHttpServletResponse option = send(signedIn(post("/api/auto-visa-all").param("visa", "false")), "null");

        assertThat(login.getStatus()).isEqualTo(400);
        assertThat(login.getContentAsString()).contains("token required");
        assertThat(option.getStatus()).isEqualTo(200);
        verifyNoInteractions(tc, jira);
    }

    /** A token as the user pastes it, with a space or a line break around it, still logs in. */
    @Test
    void aTokenPastedWithSpacesAroundItIsTaken() throws Exception {
        when(tc.currentUsername(TC)).thenReturn(Optional.of(USER));

        assertThat(send(post("/api/login"), "{\"token\":\"  " + TC + "\\n\"}").getStatus()).isEqualTo(200);
    }

    @Test
    void springsDebugAndTraceLogsNameNoToken() throws Exception {
        when(tc.currentUsername(TC)).thenReturn(Optional.of(USER));
        when(jira.myself(JIRA)).thenReturn(Optional.of("Alice"));
        when(standing.change(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        try (LogCapture log = new LogCapture(Map.of("org.springframework", Level.TRACE, "org.apache.hc", Level.TRACE))) {
            assertThat(send(post("/api/login"), "{\"token\":\"" + TC + "\"}").getStatus()).isEqualTo(200);
            assertThat(send(signedIn(post("/api/auto-visa-all").param("visa", "true")),
                "{\"jiraToken\":\"" + JIRA + "\"}").getStatus()).isEqualTo(200);
            assertThat(send(signedIn(post("/api/auto-visa-all").param("style", "true")),
                "{\"githubToken\":\"" + GH + "\"}").getStatus()).isEqualTo(200);

            assertThat(log.text()).contains("LoginRequest[token=***]");
            assertNoToken(log.text());
        }
    }

    @Test
    void recordsWithSecretsPrintNone() {
        assertNoToken(new LoginController.LoginRequest(TC).toString());
        assertNoToken(new JiraController.OptionTokens(GH, JIRA).toString());
        assertNoToken(new SessionCodec.Session(USER, TC, 1, true).toString());
        assertNoToken(new StandingVisas.GhActor(USER, TC, GH, "UTC").toString());
        assertThat(new SessionProperties(true, JIRA).toString()).doesNotContain(JIRA);
        assertThat(new GithubProperties("apache/ignite", GH, 300, null, null, null, "Iv1.client", JIRA).toString())
            .doesNotContain(GH).doesNotContain(JIRA).contains("Iv1.client");
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    /** The request as the login guard lets it through. */
    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder req) {
        return req.requestAttr(AuthInterceptor.TOKEN_ATTR, TC)
            .requestAttr(AuthInterceptor.USER_ATTR, USER);
    }

    private static void assertNoToken(String text) {
        assertThat(text).doesNotContain("FAKEtcBODY", "FAKEtcSIGN", JIRA, GH, FINE_GRAINED);
    }
}
