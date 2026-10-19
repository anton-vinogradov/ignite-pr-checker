package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/**
 * The verdict comment went out under the user's own GitHub token: a fine-grained token for their fork gets 403 in
 * apache/ignite, and its refusal took the token and the options that commit with it. The checker's account posts it
 * now, for everyone alike, and mentions the run's starter when a token proved their login: GitHub tells nobody of a
 * comment of their own, and of a mention it does.
 */
class CheckersVerdictCommentTest {
    private static final String AUTHOR = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer, jira,
        new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(build(RUN)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(RUN)));
    }

    @Test
    void theCheckersAccountMentionsTheStarterWhoseLoginIsProven() {
        standing.linkGhLogin(AUTHOR, "tc", GhUsers.of("anton-vinogradov"));
        commentOn();

        standing.sweep();

        verify(github).appComment(eq(PR), eq(AUTHOR), startsWith("@anton-vinogradov **[Ignite PR Checker]"
            + "(https://checker.example/?pr=13335)** verdict · RunAll build [9389046]"));
    }

    @Test
    void aStarterWithNoProvenLoginIsNotMentioned() {
        commentOn();

        standing.sweep();

        verify(github).appComment(eq(PR), eq(AUTHOR), startsWith("**[Ignite PR Checker]"));
    }

    /**
     * GitHub refused the checker's account, revoked or not yet allowed to comment: the comment waits for the next
     * settle, and the user's own token, kept for their commits, stays where it is.
     */
    @Test
    void aRefusalOfTheCheckersAccountKeepsTheUsersTokenAndTheCommentGoesLater() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
        standing.change(AUTHOR, "tc", null, "gh-pat", new StandingVisas.OptionChange(null, null, true, null, null,
            true));
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));

        standing.sweep();

        assertThat(standing.settings(AUTHOR).ghStored()).isTrue();
        assertThat(standing.settings(AUTHOR).ghTokenRejected()).isFalse();
        assertThat(standing.settings(AUTHOR).suites()).isTrue();
        assertThat(standing.verdictCommentDue(AUTHOR, PR, RUN)).isTrue();

        standing.sweep();

        verify(github, times(2)).appComment(eq(PR), eq(AUTHOR), anyString());
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(555L);
    }

    /**
     * While GitHub makes the checker's account wait, the comment it owes is not asked about every 10 minutes; it goes
     * at the first sweep after the wait.
     */
    @Test
    void aCommentTheCheckerCannotWriteNowWaitsWithItsPrAtRest() {
        commentOn();
        when(github.canWrite()).thenReturn(false);
        when(github.appComment(eq(PR), anyString(), anyString())).thenThrow(new GithubClient.WriteDeferred("wait"))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));
        standing.sweep();

        standing.sweep();

        verify(tc, times(1)).findRunAllBuildForPr(anyString(), eq(PR));

        when(github.canWrite()).thenReturn(true);
        standing.sweep();

        verify(github, times(2)).appComment(eq(PR), eq(AUTHOR), anyString());
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(555L);
    }

    /**
     * While the checker's account cannot write, the verdict comment of the run before waits for its superseded line
     * with the PR at rest: each sweep settled the PR again for it, asking TeamCity every 10 minutes.
     */
    @Test
    void aSupersededLineTheCheckerCannotWriteNowWaitsWithItsPrAtRest() {
        commentOn();
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(build(RUN - 1)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(RUN - 1)));
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(444L, "https://github.com/apache/ignite/pull/13335#c444"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of(PR, Set.of())));
        standing.sweep();
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(build(RUN)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(RUN)));
        when(github.canWrite()).thenReturn(false);
        when(github.appComment(eq(PR), anyString(), anyString())).thenThrow(new GithubClient.WriteDeferred("wait"));
        standing.sweep();
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));

        standing.sweep();

        verify(tc, times(2)).findRunAllBuildForPr(anyString(), eq(PR));

        when(github.canWrite()).thenReturn(true);
        doReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555")).when(github)
            .appComment(eq(PR), anyString(), anyString());
        when(github.commentBody(444L)).thenReturn(Optional.of("verdict of RunAll 9389045"));
        standing.sweep();

        verify(github).appEdit(eq(PR), eq(AUTHOR), eq(444L), contains("by the newer RunAll [9389046]"));
        assertThat(standing.verdictCommentId(AUTHOR, PR, RUN)).hasValue(555L);
    }

    @Test
    void theCommentSaysWhyNoVisaCame() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "For TC debug", null, null, null, null)));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        standing.change(AUTHOR, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, null, true, null, null));

        standing.sweep();

        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github).appComment(eq(PR), eq(AUTHOR), contains("\n\n_No JIRA visa: the PR title names no IGNITE"
            + " ticket._"));
    }

    /** The token an earlier version stored for the PR comment alone goes once it proved the login, kept as proven. */
    @Test
    void aTokenKeptForThePrCommentAloneGoesOnceItProvedTheLogin(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        mapper.writeValue(file.toFile(), mapper.readTree("""
            {"enrollments": [{"username": "olegvlsk", "tcToken": "%s", "ghToken": "%s", "ghLogin": "oleg-vlsk",
              "enabledAt": 1759312800000, "autoVisa": false, "autoRerun": false, "ghComment": true,
              "styleFix": false, "commands": true}]}
            """.formatted(codec.encryptString("tc-oleg"), codec.encryptString("gh-oleg"))));
        standing.loadFrom(file);
        when(github.ghIdentity("gh-oleg")).thenReturn(Optional.of(GhUsers.of("oleg-vlsk")));
        assertThat(standing.settings("olegvlsk").ghStored()).as("until it is asked whose it is").isTrue();

        standing.ensureGhLogins();

        assertThat(standing.settings("olegvlsk")).satisfies(st -> {
            assertThat(st.ghStored()).isFalse();
            assertThat(st.ghTokenRejected()).isFalse();
            assertThat(st.loginUnproven()).isFalse();
            assertThat(st.gh()).isTrue();
            assertThat(st.commands()).isTrue();
        });
    }

    private void commentOn() {
        standing.change(AUTHOR, "tc", null, null, new StandingVisas.OptionChange(null, null, true, null, null));
    }

    private static TcModel.Build build(long id) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/13335/head", null, null, null, null, null, null, null,
            null, new TcModel.Triggered("user", new TcModel.User(AUTHOR)), null, null, null, null, null);
    }

    private static AnalysisResult verdict(long run) {
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);

        return new AnalysisResult(PR, run, "pull/13335/head", System.currentTimeMillis(), List.of(blocker), List.of(),
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
