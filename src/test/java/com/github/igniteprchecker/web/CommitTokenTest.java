package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The PR comment needed a classic GitHub token with public_repo, and the server kept whatever token GitHub put a name
 * to, admin:org and delete_repo included; a committer's token pushes to apache/ignite. The checker's account posts
 * the comment now, a token is kept only for the options that commit, and only one that does no more than committing
 * needs. A checker with no account of its own offers none of the options that write to GitHub.
 */
class CommitTokenTest {
    private static final String USER = "avinogradov";

    private final GithubClient github = AppWriting.github();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = standing();

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(TcClient.class),
        mock(BlockerAnalyzer.class), codec, mock(VisaService.class), mock(VisaSubscriptions.class), standing,
        mock(PendingCommits.class), false);

    @Test
    void thePrCommentNeedsNoTokenOfTheUser() {
        ResponseEntity<?> res = controller.standingVisa(null, null, true, null, null, null, "tc", USER, null, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.ghOn(USER)).isTrue();
        assertThat(standing.settings(USER).ghStored()).isFalse();
    }

    @Test
    void aClassicTokenThatDoesMoreThanCommitIsRefused() {
        owner("ghp_admin", "public_repo", "admin:org");

        ResponseEntity<?> res = controller.standingVisa(null, null, null, null, null, true, "tc", USER, null,
            "ghp_admin");

        assertThat(res.getStatusCode().value()).isEqualTo(412);
        Map<?, ?> body = (Map<?, ?>)res.getBody();
        assertThat(body.get("need")).isEqualTo("github");
        assertThat(String.valueOf(body.get("error"))).startsWith("this classic token has admin:org: with it, anyone who"
            + " reads it could administer");
        assertThat(standing.settings(USER).ghStored()).isFalse();
    }

    @Test
    void aClassicTokenWithRepoIsKeptAndTheWarningStaysAcrossARestart(@TempDir Path dir) throws Exception {
        owner("ghp_repo", "repo");

        ResponseEntity<?> res = controller.standingVisa(null, null, null, null, null, true, "tc", USER, null,
            "ghp_repo");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = standing();
        restarted.loadFrom(file);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(((StandingVisas.Settings)res.getBody()).ghTokenNote()).contains("with the repo scope to your"
            + " private ones as well; public_repo is enough");
        assertThat(restarted.settings(USER).ghTokenNote()).isEqualTo(standing.settings(USER).ghTokenNote());
        assertThat(restarted.settings(USER).ghStored()).isTrue();
    }

    @Test
    void aFineGrainedTokenIsKeptWithNoWarning() {
        owner("github_pat_fork", (String[])null);

        controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "github_pat_fork");

        assertThat(standing.settings(USER).ghStored()).isTrue();
        assertThat(standing.settings(USER).ghTokenNote()).isNull();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.ghToken()).isEqualTo("github_pat_fork"));
    }

    /** No GITHUB_TOKEN: nothing could post the comment, react to a command or tell the run. */
    @Test
    void withoutTheCheckersAccountTheOptionsThatWriteToGithubAreNotOffered() {
        when(github.writes()).thenReturn(false);

        ResponseEntity<?> comment = controller.standingVisa(null, null, true, null, null, null, "tc", USER, null,
            null);
        ResponseEntity<?> rerun = controller.standingVisa(null, true, null, null, null, null, "tc", USER, null, null);
        ResponseEntity<?> link = new GithubLinkController(github, standing, mock(AuthInterceptor.class),
            "https://prc.example.org").link(null, null, "tc", USER);

        assertThat(comment.getStatusCode().value()).isEqualTo(409);
        assertThat(((Map<?, ?>)comment.getBody()).get("error")).isEqualTo("not available: this checker has no GitHub"
            + " account to write with (GITHUB_TOKEN); its operator can add one");
        assertThat(rerun.getStatusCode().value()).as("auto re-run writes nothing to GitHub").isEqualTo(200);
        assertThat(link.getStatusCode().value()).isEqualTo(409);
        assertThat(standing.settings(USER)).satisfies(st -> {
            assertThat(st.githubAvailable()).isFalse();
            assertThat(st.gh()).isFalse();
            assertThat(st.commands()).isFalse();
            assertThat(st.rerun()).isTrue();
        });
    }

    /** The page posts a switch with no body, or with the token of an option that commits in a JSON one. */
    @Test
    void theSwitchTakesTheTokenFromItsBodyAndNeedsNone() throws Exception {
        owner("github_pat_fork", (String[])null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/auto-visa-all").param("rerun", "true").requestAttr(AuthInterceptor.TOKEN_ATTR, "tc")
            .requestAttr(AuthInterceptor.USER_ATTR, USER)).andExpect(status().isOk());
        mvc.perform(post("/api/auto-visa-all").param("suites", "true").contentType(MediaType.APPLICATION_JSON)
            .content("{\"githubToken\":\" github_pat_fork \"}").requestAttr(AuthInterceptor.TOKEN_ATTR, "tc")
            .requestAttr(AuthInterceptor.USER_ATTR, USER)).andExpect(status().isOk());

        assertThat(standing.rerunOn(USER)).isTrue();
        assertThat(standing.actor(USER)).hasValueSatisfying(a -> assertThat(a.ghToken()).isEqualTo("github_pat_fork"));
    }

    private void owner(String token, String... scopes) {
        when(github.ghIdentity(token)).thenReturn(Optional.of(GhUsers.of("anton-vinogradov")));
        when(github.tokenOwner(token)).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), scopes == null ? null : List.of(scopes))));
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, mock(TcClient.class), github, mock(BlockerAnalyzer.class),
            mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
            mock(PendingCommits.class));
    }
}
