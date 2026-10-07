package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The only visa in IGNITE-28938 still reads "(x) 3 blockers… Auto re-run #1 in progress", and PR 13427 was merged
 * with it: the sweep looked at the latest RunAll of the 50 most recently updated PRs only, and nothing ever ended a
 * living comment whose run a newer one had replaced or whose PR was closed. standing-visas.json on prod still holds
 * re-run waves from August.
 */
class ThreadEndingTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13427;

    private static final long OLD = 9380000L;

    private static final long NEW = 9386000L;

    private static final long COMMENT = 555L;

    private static final String VISA = "18000001";

    private static final String ISSUE = "IGNITE-28938";

    private static final String IN_PROGRESS_GH = "**[Ignite PR Checker](https://checker.example/?pr=13427)** verdict"
        + "\n\n❌ **3 blocker(s) in 2 suite(s):**\n- Cache 1: `Cache1Test.test`"
        + "\n\n⏳ _Auto re-run **#1** (of up to 2) in progress — 2 blocker suite(s) re-queued. This comment updates when"
        + " they settle._";

    private static final String IN_PROGRESS_JIRA = "[Ignite PR Checker|https://checker.example/?pr=13427] verdict"
        + "\r\n\r\n(x) *3 blocker(s) in 2 suite(s):*\r\n- Cache 1: {{Cache1Test.test}}"
        + "\r\n\r\n⏳ _Auto re-run *#1* (of up to 2) in progress — 2 blocker suite(s) re-queued. This comment updates"
        + " when they settle._";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, mock(BlockerAnalyzer.class),
        jira, new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
        mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        standing.change(USER, "tc", "jira-pat", "gh-pat", new StandingVisas.OptionChange(true, true, true, false, null));
        livingCommentsOf(OLD, dir);
        when(github.commentBody(COMMENT)).thenReturn(Optional.of(IN_PROGRESS_GH));
        when(jira.commentBody("jira-pat", ISSUE, VISA)).thenReturn(Optional.of(IN_PROGRESS_JIRA));
    }

    @Test
    void aCommentLeftInProgressByANewerRunGetsItsLastLine() {
        listed();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(finished(NEW, "bob")));

        standing.sweep();
        standing.sweep();

        String replaced = "Re-runs stopped: RunAll " + NEW + " replaced this run before they settled. The verdict"
            + " above is this run's last; the newest is on ";
        verify(github).updatePrComment("gh-pat", COMMENT, IN_PROGRESS_GH.substring(0, IN_PROGRESS_GH.indexOf("\n\n⏳"))
            + "\n\n🛑 _" + replaced + "[the checker's page](https://checker.example/?pr=13427)._");
        verify(jira).updateComment("jira-pat", ISSUE, VISA, IN_PROGRESS_JIRA.substring(0,
            IN_PROGRESS_JIRA.indexOf("\r\n\r\n⏳")) + "\n\n_" + replaced + "[the checker's page|https://checker.example/?pr=13427]._");
        verify(github, times(1)).commentBody(COMMENT);
        assertThat(standing.waveStatus(PR, OLD)).isEmpty();
    }

    /** A comment that already carries its final verdict keeps it; it only says that a newer run superseded it. */
    @Test
    void aCommentThatAlreadyEndedIsOnlyMarkedSuperseded() {
        listed();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(finished(NEW, "bob")));
        String verdict = "**[Ignite PR Checker](…)** verdict\n\n✅ **No blockers**";
        when(github.commentBody(COMMENT)).thenReturn(Optional.of(verdict));

        standing.sweep();
        standing.sweep();

        verify(github, times(1)).updatePrComment(anyString(), anyLong(), anyString());
        verify(github).updatePrComment("gh-pat", COMMENT, verdict + "\n\n🔁 _Superseded by the newer RunAll [" + NEW
            + "](https://ci2.example/build/" + NEW + ") — its verdict is on [the checker's page]"
            + "(https://checker.example/?pr=13427)._");
        verify(github, times(1)).commentBody(COMMENT);
    }

    /**
     * The old comment is read under the app's token, whose 60 anonymous requests an hour the PR list and /status
     * share, or which the operator has just revoked: GitHub refusing it is no reason to drop the owner's own PAT.
     */
    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedReadUnderTheAppsTokenKeepsTheOwnersToken(int status) {
        listed();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(finished(NEW, "bob")));
        when(github.commentBody(COMMENT)).thenThrow(HttpClientErrorException.create(HttpStatusCode.valueOf(status),
            "API rate limit exceeded", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        standing.sweep();

        StandingVisas.Settings settings = standing.settings(USER);
        assertThat(settings.ghStored()).isTrue();
        assertThat(settings.gh()).isTrue();
        assertThat(settings.ghTokenRejected()).isFalse();

        doReturn(Optional.of(IN_PROGRESS_GH)).when(github).commentBody(COMMENT);
        standing.sweep();

        verify(github).updatePrComment(eq("gh-pat"), eq(COMMENT), contains("Re-runs stopped: RunAll " + NEW));
    }

    /** The owner's PAT itself refused on the edit: it is dropped, as everywhere else. */
    @Test
    void aRefusedEditDropsTheOwnersToken() {
        listed();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(finished(NEW, "bob")));
        doThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Bad credentials", HttpHeaders.EMPTY,
            new byte[0], StandardCharsets.UTF_8)).when(github).updatePrComment(eq("gh-pat"), eq(COMMENT), anyString());

        standing.sweep();

        assertThat(standing.settings(USER).ghStored()).isFalse();
        assertThat(standing.settings(USER).ghTokenRejected()).isTrue();
    }

    /** Its owner switched off everything that settles runs and kept checkstyle autofix, with the GitHub token. */
    @Test
    void aCommentWhoseOptionsWereSwitchedOffSaysSo() {
        listed();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(finished(OLD, USER)));
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, false, false, true, null));

        standing.sweep();

        verify(github).updatePrComment("gh-pat", COMMENT, IN_PROGRESS_GH.substring(0, IN_PROGRESS_GH.indexOf("\n\n⏳"))
            + "\n\n⏹ _Re-runs no longer followed: the options that settle this run were switched off. The verdict"
            + " above is the last known._");
    }

    /**
     * v1.20.11 kept one switch-on time for all options and moved it when one more was switched on while the re-runs
     * went: the run's options are all on, but none was by that time.
     */
    @Test
    void aCommentOfARunFinishedBeforeItsOptionsWereSwitchedOnSaysTheyChanged() {
        listed();
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(finished(OLD, USER,
            TcDates.format(System.currentTimeMillis() / 1000 - 3_600))));

        standing.sweep();

        verify(github).updatePrComment("gh-pat", COMMENT, IN_PROGRESS_GH.substring(0, IN_PROGRESS_GH.indexOf("\n\n⏳"))
            + "\n\n⏹ _Re-runs no longer followed: the options that settle this run were changed after it finished."
            + " The verdict above is the last known._");
        assertThat(standing.buildHandled(USER, PR, OLD)).isTrue();
    }

    /** PR 13427 dropped out of the sweep's list once merged; its comments and waves were never looked at again. */
    @Test
    void aPrMergedBeforeTheReRunsSettledEndsItsComments() {
        when(github.openPrs()).thenReturn(List.of());
        when(github.pullState(PR)).thenReturn(Optional.of(new GithubClient.PullState(ISSUE + " Fix it", false, true)));

        standing.sweep();

        verify(github).updatePrComment(eq("gh-pat"), eq(COMMENT),
            eq(IN_PROGRESS_GH.substring(0, IN_PROGRESS_GH.indexOf("\n\n⏳"))
                + "\n\n🏁 _The PR was merged before the re-runs settled: the verdict above is the last known._"));
        verify(jira).updateComment(eq("jira-pat"), eq(ISSUE), eq(VISA), anyString());
        assertThat(standing.waveStatus(PR, OLD)).isEmpty();
        assertThat(standing.runEnd(PR, OLD)).contains(new StandingVisas.RunEnd(0, true));
    }

    private void listed() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Fix it", null, null, null, null)));
    }

    /** What v1.20.11 left on disk: the run's comment and visa posted while its first wave went, and the wave. */
    private void livingCommentsOf(long buildId, Path dir) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)snap.get("enrollments").get(0);
        enrollment.putObject("posted").put(String.valueOf(PR), buildId - 1000);
        enrollment.putObject("ghThreads").putObject(String.valueOf(PR)).put("buildId", buildId)
            .put("commentId", COMMENT);
        enrollment.putObject("jiraThreads").putObject(String.valueOf(PR)).put("buildId", buildId)
            .put("commentId", VISA);
        snap.remove("waves");
        snap.putObject("retries").putObject(String.valueOf(PR)).put("buildId", buildId).put("attempts", 1)
            .put("what", "2 blocker suite(s)").putNull("note").putArray("history").add("2 blocker suite(s)");
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }

    private static TcModel.Build finished(long id, String by) {
        return finished(id, by, null);
    }

    private static TcModel.Build finished(long id, String by, String finishDate) {
        return new TcModel.Build(id, "FAILURE", "finished", "pull/13427/head", null, null, null, null, finishDate, null,
            null, null, new TcModel.Triggered("user", new TcModel.User(by)), null, null, null, null, null);
    }
}
