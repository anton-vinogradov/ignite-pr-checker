package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.CheckstyleRunner;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * GitHub answers 403 to a secondary rate limit too, and the checker took any 403 for a revoked token: a working PAT
 * was dropped, three options went off and the author read "GitHub rejected your personal access token". A token
 * saved while GitHub was down was "rejected" as well. Only GitHub's 401 says the token is dead now.
 */
class PatRefusalRuleTest {
    private static final int PR = 13800;

    private static final long CHAIN = 9400000L;

    private static final HttpClientErrorException RATE_LIMITED = HttpClientErrorException.create(
        HttpStatusCode.valueOf(403), "You have exceeded a secondary rate limit", HttpHeaders.EMPTY, new byte[0],
        StandardCharsets.UTF_8);

    private static final String PATH = "modules/core/src/main/java/p/C.java";

    private static final String CHECKSTYLE = """
        <?xml version="1.0"?>
        <!DOCTYPE module PUBLIC "-//Puppy Crawl//DTD Check Configuration 1.3//EN"
            "http://www.puppycrawl.com/dtds/configuration_1_3.dtd">
        <module name="Checker">
            <property name="charset" value="UTF-8"/>
            <module name="TreeWalker">
                <module name="ModifierOrder"/>
            </module>
        </module>
        """;

    private static final String SUPPRESSIONS = "<?xml version=\"1.0\"?><!DOCTYPE suppressions PUBLIC "
        + "\"-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN\" "
        + "\"https://checkstyle.org/dtds/suppressions_1_2.dtd\"><suppressions/>";

    private static final String MODIFIERS_OUT_OF_ORDER = """
        package p;

        /** C. */
        public class C {
            /** F. */
            static public final int F = 1;
        }
        """;

    private final GithubClient github = mock(GithubClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
        mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("Author")));
        standing.change("author", "tc", null, "gh-pat",
            new StandingVisas.OptionChange(false, false, true, false, true));
    }

    @Test
    void aRateLimitedEditKeepsTheToken() {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(1L, "/run-all",
            "https://github.com/apache/ignite/pull/" + PR + "#issuecomment-1", Instant.now().toString(),
            GhUsers.of("Author"))));
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(new TcModel.Build(CHAIN, null, "queued", "pull/13800/head",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        doThrow(RATE_LIMITED).when(github).updatePrComment(eq("gh-pat"), eq(1L), anyString());

        new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"))
            .poll();

        assertThat(standing.ghTokenRejected("author")).isFalse();
        assertThat(standing.ghOn("author")).isTrue();
        verify(github, never()).addPrCommentAsAppWithId(anyInt(), contains("GitHub rejected your personal access"));
    }

    @Test
    void aTokenIsRejectedOnlyWhenGithubSays401() {
        RestClient.Builder http = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        GithubClient client = new GithubClient(new GithubProperties("apache/ignite", "app-token", 300), mapper,
            new Metrics(mapper), http.build());
        server.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("https://api.github.com/user")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.ghIdentity("dead-pat")).isEmpty();
        assertThatThrownBy(() -> client.ghIdentity("pat")).isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> client.ghIdentity("pat"))
            .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
    }

    /** The autofix commits under the author's own token: GitHub refusing it drops it, as everywhere else. */
    @Test
    void theAutofixDropsATokenGithubRefusesAndOnlyThat() {
        StyleFixService styleFix = new StyleFixService(github, new CheckstyleRunner(github,
            new GithubProperties("apache/ignite", null, null)), standing);
        StandingVisas.GhActor author = standing.actor("author").orElseThrow();
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle.xml"))).thenReturn(CHECKSTYLE);
        when(github.rawFile(anyString(), anyString(), contains("suppressions"))).thenReturn(SUPPRESSIONS);
        when(github.prHead(PR))
            .thenReturn(new GithubClient.PrHead("Author", "author/ignite", "ignite-28867", "f195e0c"));
        when(github.prJavaFiles(PR)).thenReturn(List.of(PATH));
        when(github.rawFileUpTo("author/ignite", "f195e0c", PATH, CheckstyleRunner.MAX_FILE_BYTES))
            .thenReturn(Optional.of(MODIFIERS_OUT_OF_ORDER));
        when(github.commitFiles(eq("gh-pat"), anyString(), anyString(), anyString(), any(), anyString()))
            .thenThrow(RATE_LIMITED).thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        String limited = styleFix.fixForCommand(PR, author, "Author");

        assertThat(standing.ghTokenRejected("author")).isFalse();
        assertThat(limited).startsWith("🎨 _Checkstyle autofix failed");

        String refused = styleFix.fixForCommand(PR, author, "Author");

        assertThat(standing.ghTokenRejected("author")).isTrue();
        assertThat(refused).isEqualTo("🎨 _Checkstyle autofix: GitHub refused your token, so the fix was not pushed —"
            + " running as-is._");
    }
}
