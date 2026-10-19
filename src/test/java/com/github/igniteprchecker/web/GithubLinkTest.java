package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.LogCapture;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.StubbedGithub;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Linking a GitHub account meant creating a GitHub token that could do nothing and pasting it into ⚙, which nobody
 * understood. Link GitHub now goes through the checker's OAuth App: GitHub asks the user, sends them back with a code,
 * the code buys a token that names the account, and the token is revoked at once. The callback links the account only
 * for the session that began the link, within ten minutes, once.
 */
@ExtendWith(OutputCaptureExtension.class)
class GithubLinkTest {
    private static final String CALLBACK = "https://prc.example.org/api/github/callback";

    private static final String ONCE = "gho_onceOnly0123456789";

    private static final GithubProperties OAUTH_APP = new GithubProperties("apache/ignite", "app-token", 300, null,
        null, null, "Iv1.client", "client-secret");

    private final RestClient.Builder http = RestClient.builder().configureMessageConverters(OutboundHttp::json);

    private final MockRestServiceServer gh = MockRestServiceServer.bindTo(http).build();

    private final GithubClient github = StubbedGithub.over(OAUTH_APP, "https://prc.example.org/", http.build());

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    private final AtomicLong now = new AtomicLong(1_760_000_000_000L);

    private final GithubLinkController links = new GithubLinkController(github, standing,
        new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class),
            new Logins(mapper, mock(UserDirectory.class)), standing), now::get);

    @Test
    void theAccountGitHubNamesIsLinkedAndItsTokenRevoked(@TempDir Path dir, CapturedOutput output) throws Exception {
        URI authorize = start("alice", "tc-alice", 13575);
        Map<String, String> asked = params(authorize);
        gh.expect(requestTo("https://github.com/login/oauth/access_token")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Accept", "application/json"))
            .andExpect(content().string(allOf(containsString("client_id=Iv1.client"),
                containsString("client_secret=client-secret"), containsString("code=code-1"),
                containsString("redirect_uri=https%3A%2F%2Fprc.example.org%2Fapi%2Fgithub%2Fcallback"))))
            .andRespond(withSuccess("{\"access_token\":\"" + ONCE + "\",\"token_type\":\"bearer\",\"scope\":\"\"}",
                MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/user")).andExpect(header("Authorization", "Bearer " + ONCE))
            .andRespond(withSuccess("{\"login\":\"Alice-V\",\"id\":4242}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/applications/Iv1.client/token"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString("Iv1.client:client-secret".getBytes(StandardCharsets.UTF_8))))
            .andExpect(content().json("{\"access_token\":\"" + ONCE + "\"}"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        ResponseEntity<Void> back = links.callback("code-1", asked.get("state"), null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(authorize.toString()).startsWith("https://github.com/login/oauth/authorize?");
        assertThat(asked).containsEntry("client_id", "Iv1.client").containsEntry("allow_signup", "false")
            .containsEntry("redirect_uri", CALLBACK).doesNotContainKey("scope");
        assertThat(back.getStatusCode().value()).isEqualTo(302);
        assertThat(back.getHeaders().getLocation()).hasToString("/?pr=13575&github=linked");
        assertThat(standing.ghLoginOf("alice")).isEqualTo("Alice-V");
        assertThat(standing.commandsOn("alice")).as("the link is how PR commands go on").isTrue();
        assertThat(standing.actorByGhUser(new GithubClient.GhUser("alice-v", 4242))).hasValueSatisfying(a -> {
            assertThat(a.username()).isEqualTo("alice");
            assertThat(a.tcToken()).isEqualTo("tc-alice");
            assertThat(a.ghToken()).isNull();
        });
        standing.saveTo(dir.resolve("standing-visas.json"));
        assertThat(Files.readString(dir.resolve("standing-visas.json"))).contains("\"ghId\":4242")
            .doesNotContain(ONCE);
        assertThat(back.getHeaders().toString()).doesNotContain(ONCE);
        assertThat(output.getAll()).doesNotContain(ONCE);
    }

    /**
     * With Spring's web logging at DEBUG, the form that buys the token went into the log whole, the OAuth App's client
     * secret in it, and so did the body that revokes the user's token.
     */
    @Test
    void theClientSecretAndTheUsersTokenStayOutOfTheDebugLog() {
        URI authorize = start("alice", "tc-alice", null);
        expectAccount("code-1", "Alice-V", 4242);

        try (LogCapture log = new LogCapture(Map.of("org.springframework", Level.TRACE, "org.apache.hc",
            Level.TRACE))) {
            links.callback("code-1", state(authorize), null, browserOf("alice", "tc-alice"));

            gh.verify();
            assertThat(log.text()).contains("DefaultRestClient").doesNotContain("client-secret", ONCE);
        }
    }

    /** bob sends alice the address GitHub gave him back, his state and his code in it: nothing is linked. */
    @Test
    void aStateOfAnotherSessionLinksNothing() {
        String bobs = state(start("bob", "tc-bob", null));

        ResponseEntity<Void> back = links.callback("code-of-bob", bobs, null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?github=expired");
        assertThat(standing.ghLoginOf("alice")).isNull();
        assertThat(links.callback("code-of-bob", bobs, null, browserOf("bob", "tc-bob")).getHeaders().getLocation())
            .as("the state is spent").hasToString("/?github=expired");
        assertThat(standing.ghLoginOf("bob")).isNull();
    }

    /** The same user logged in again, with another TeamCity token, in another browser: that is another session. */
    @Test
    void anotherLoginOfTheSameUserIsAnotherSession() {
        String state = state(start("alice", "tc-alice", null));

        ResponseEntity<Void> back = links.callback("code-1", state, null, browserOf("alice", "tc-alice-2"));

        assertThat(back.getHeaders().getLocation()).hasToString("/?github=expired");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    @Test
    void aStateOlderThanTenMinutesLinksNothing() {
        String state = state(start("alice", "tc-alice", 13575));
        now.addAndGet(GithubLinkController.STATE_TTL.toMillis() + 1);

        ResponseEntity<Void> back = links.callback("code-1", state, null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?github=expired");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    @Test
    void aStateWorksOnce() {
        String state = state(start("alice", "tc-alice", null));
        expectAccount("code-1", "Alice-V", 4242);
        links.callback("code-1", state, null, browserOf("alice", "tc-alice"));
        standing.unlinkGhLogin("alice");

        ResponseEntity<Void> again = links.callback("code-1", state, null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(again.getHeaders().getLocation()).hasToString("/?github=expired");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    /** A second click on Link GitHub replaces the first link of the session. */
    @Test
    void aNewLinkOfTheSessionEndsItsEarlierOne() {
        String first = state(start("alice", "tc-alice", null));
        start("alice", "tc-alice", null);

        assertThat(links.callback("code-1", first, null, browserOf("alice", "tc-alice")).getHeaders().getLocation())
            .hasToString("/?github=expired");
    }

    /** The browser came back logged out: the state proves nothing without the session it was made for. */
    @Test
    void aCallbackWithoutALoginLinksNothing() {
        String state = state(start("alice", "tc-alice", 13575));

        ResponseEntity<Void> back = links.callback("code-1", state, null, new MockHttpServletRequest());

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?github=signin");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    @Test
    void aUserWhoDeclinesOnGitHubIsToldSo() {
        String state = state(start("alice", "tc-alice", 13575));

        ResponseEntity<Void> back = links.callback(null, state, "access_denied", browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?pr=13575&github=denied");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    /**
     * A link begun on the Python client's PR #69 came back to Ignite's PR #69. The page comes back to the client's PR,
     * and a repo the page does not know adds nothing to the address.
     */
    @Test
    void theLinkComesBackToTheClientsPr() {
        ResponseEntity<?> asked = links.link("Europe/Moscow", 69, "python", "tc-alice", "alice");
        String state = state(URI.create(String.valueOf(((Map<?, ?>)asked.getBody()).get("url"))));

        ResponseEntity<Void> back = links.callback(null, state, "access_denied", browserOf("alice", "tc-alice"));

        assertThat(back.getHeaders().getLocation()).hasToString("/?repo=python&pr=69&github=denied");

        ResponseEntity<?> other = links.link("Europe/Moscow", 69, "evil.example/x", "tc-alice", "alice");
        String otherState = state(URI.create(String.valueOf(((Map<?, ?>)other.getBody()).get("url"))));

        assertThat(links.callback(null, otherState, "access_denied", browserOf("alice", "tc-alice")).getHeaders()
            .getLocation()).hasToString("/?pr=69&github=denied");
    }

    /** An expired or used code: GitHub answers 200 with an error and no token. */
    @Test
    void aCodeGitHubRefusesLinksNothing() {
        String state = state(start("alice", "tc-alice", null));
        gh.expect(requestTo("https://github.com/login/oauth/access_token")).andRespond(withSuccess(
            "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}",
            MediaType.APPLICATION_JSON));

        ResponseEntity<Void> back = links.callback("code-1", state, null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?github=failed");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    @Test
    void gitHubDownLinksNothing() {
        String state = state(start("alice", "tc-alice", null));
        gh.expect(requestTo("https://github.com/login/oauth/access_token")).andRespond(withServerError());

        assertThat(links.callback("code-1", state, null, browserOf("alice", "tc-alice")).getHeaders().getLocation())
            .hasToString("/?github=failed");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    /** The token named the account; that GitHub did not take it back changes nothing for the link. */
    @Test
    void aRevocationGitHubRefusesKeepsTheLink(CapturedOutput output) {
        String state = state(start("alice", "tc-alice", null));
        gh.expect(requestTo("https://github.com/login/oauth/access_token"))
            .andRespond(withSuccess("{\"access_token\":\"" + ONCE + "\"}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"Alice-V\",\"id\":4242}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/applications/Iv1.client/token")).andRespond(withServerError());

        ResponseEntity<Void> back = links.callback("code-1", state, null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?github=linked");
        assertThat(standing.ghLoginOf("alice")).isEqualTo("Alice-V");
        assertThat(output.getAll()).contains("GitHub did not revoke the token of a GitHub account link")
            .doesNotContain(ONCE);
    }

    /** GitHub gave a token but failed to say whose it is: the token is revoked all the same. */
    @Test
    void aTokenIsRevokedWhenGithubCannotNameItsAccount() {
        String state = state(start("alice", "tc-alice", null));
        gh.expect(requestTo("https://github.com/login/oauth/access_token"))
            .andRespond(withSuccess("{\"access_token\":\"" + ONCE + "\"}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/user")).andRespond(withServerError());
        gh.expect(requestTo("https://api.github.com/applications/Iv1.client/token"))
            .andExpect(method(HttpMethod.DELETE)).andExpect(content().json("{\"access_token\":\"" + ONCE + "\"}"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        ResponseEntity<Void> back = links.callback("code-1", state, null, browserOf("alice", "tc-alice"));

        gh.verify();
        assertThat(back.getHeaders().getLocation()).hasToString("/?github=failed");
        assertThat(standing.ghLoginOf("alice")).isNull();
    }

    /** The token is revoked even when it names no account. */
    @Test
    void aTokenThatNamesNoAccountIsRevokedToo() {
        String state = state(start("alice", "tc-alice", null));
        gh.expect(requestTo("https://github.com/login/oauth/access_token"))
            .andRespond(withSuccess("{\"access_token\":\"" + ONCE + "\"}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        gh.expect(requestTo("https://api.github.com/applications/Iv1.client/token"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(links.callback("code-1", state, null, browserOf("alice", "tc-alice")).getHeaders().getLocation())
            .hasToString("/?github=failed");
        gh.verify();
    }

    @Test
    void withoutAnOAuthAppThereIsNoLink() {
        GithubClient plain = StubbedGithub.over(new GithubProperties("apache/ignite", "app-token", 300),
            "https://prc.example.org", http.build());
        GithubLinkController off = new GithubLinkController(plain, standing,
            new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class),
            new Logins(mapper, mock(UserDirectory.class)), standing), now::get);

        ResponseEntity<?> res = off.link(null, null, "tc-alice", "alice");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).isEqualTo(Map.of("error", "Link GitHub is not set up on this checker"));
        assertThat(plain.linksAccounts()).isFalse();
        assertThat(github.linksAccounts()).isTrue();
    }

    /**
     * install.sh writes APP_PUBLIC_URL empty, and an operator set up the OAuth App but not the address: GitHub has
     * nowhere to send a user back. ⚙ offered Link GitHub, which always answered 409, and hid what links an account
     * instead.
     */
    @Test
    void withoutAPublicAddressThereIsNoLink() {
        GithubClient nowhere = StubbedGithub.over(OAUTH_APP, " ", http.build());
        StandingVisas standingThere = new StandingVisas(mapper, codec, mock(TcClient.class), nowhere,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
        GithubLinkController off = new GithubLinkController(nowhere, standingThere,
            new AuthInterceptor(codec, mock(Warmer.class), mock(UserDirectory.class),
            new Logins(mapper, mock(UserDirectory.class)), standingThere), now::get);

        ResponseEntity<?> res = off.link(null, null, "tc-alice", "alice");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(nowhere.linksAccounts()).isFalse();
        assertThat(standingThere.settings("alice").githubLinkable()).as("⚙ shows no Link GitHub").isFalse();
        assertThat(standingThere.settings("alice").githubAvailable()).isTrue();
    }

    private URI start(String username, String tcToken, Integer pr) {
        ResponseEntity<?> res = links.link("Europe/Moscow", pr, tcToken, username);
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        return URI.create(String.valueOf(((Map<?, ?>)res.getBody()).get("url")));
    }

    private static String state(URI authorize) {
        return params(authorize).get("state");
    }

    private static Map<String, String> params(URI uri) {
        Map<String, String> out = new HashMap<>();
        UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap()
            .forEach((k, v) -> out.put(k, URLDecoder.decode(v, StandardCharsets.UTF_8)));

        return out;
    }

    private void expectAccount(String code, String login, long id) {
        gh.expect(requestTo("https://github.com/login/oauth/access_token"))
            .andExpect(content().string(containsString("code=" + code)))
            .andRespond(withSuccess("{\"access_token\":\"" + ONCE + "\"}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/user"))
            .andRespond(withSuccess("{\"login\":\"" + login + "\",\"id\":" + id + "}", MediaType.APPLICATION_JSON));
        gh.expect(requestTo("https://api.github.com/applications/Iv1.client/token"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
    }

    private MockHttpServletRequest browserOf(String username, String tcToken) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(new SessionCodec.Session(username, tcToken))));

        return req;
    }
}
