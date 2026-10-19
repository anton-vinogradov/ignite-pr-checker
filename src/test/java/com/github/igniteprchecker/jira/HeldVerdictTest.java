package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A JIRA token that expired cost the visa of the run it was refused on: the run was marked handled, a fresh token
 * posted nothing for it, and /status counted the visa as posted. Any 403 also dropped a working token: GitHub answers
 * 403 to a rate limit, JIRA to a ticket the user may not comment on, and ASF JIRA may answer a dead token with neither.
 */
class HeldVerdictTest {
    private static final String AUTHOR = "avinogradov";

    private static final int PR = 13335;

    private static final long FIRST = 9389046L;

    private static final long SECOND = 9391200L;

    private static final String ISSUE = "IGNITE-28867";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"),
        "https://checker.example");

    private final StandingVisas standing = standing();

    @BeforeEach
    void setUp() {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(jira.myself("fresh-jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(jira.addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/" + ISSUE));
        finished(FIRST, AUTHOR);
    }

    @Test
    void aVisaARefusedTokenHeldBackGoesOutWithTheNextOne() {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString())).thenThrow(status(401));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);

        standing.sweep();

        assertThat(standing.jiraTokenRejected(AUTHOR)).isTrue();
        assertThat(standing.visaOn(AUTHOR)).isFalse();
        assertThat(standing.postedCount()).as("nothing went out").isZero();

        standing.change(AUTHOR, "tc", "fresh-jira-pat", null, new StandingVisas.OptionChange(true, null, null, null,
            null));

        verify(jira, timeout(5_000)).addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), contains("RunAll"));
        assertThat(standing.postedCount()).isEqualTo(1);
    }

    @Test
    void aHeldVisaOfARunANewerOneReplacedNeverGoesOut() {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString())).thenThrow(status(401));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);
        standing.sweep();
        finished(SECOND, "nizhikov");
        standing.sweep();

        standing.change(AUTHOR, "tc", "fresh-jira-pat", null, new StandingVisas.OptionChange(true, null, null, null,
            null));

        verify(jira, after(500).never()).addCommentWithId(eq("fresh-jira-pat"), anyString(), anyString());
    }

    /** The checker was redeployed between the refusal and the fresh token. */
    @Test
    void aHeldVisaGoesOutOnceAcrossARestart(@TempDir Path dir) throws Exception {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString())).thenThrow(status(401));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);
        standing.sweep();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = standing();
        restarted.loadFrom(file);

        restarted.change(AUTHOR, "tc", "fresh-jira-pat", null, new StandingVisas.OptionChange(true, null, null, null,
            null));
        verify(jira, timeout(5_000)).addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), anyString());
        restarted.sweep();
        restarted.sweep();

        verify(jira, times(1)).addCommentWithId(eq("fresh-jira-pat"), anyString(), anyString());
    }

    @Test
    void aRateLimitedCommentKeepsTheTokenAndGoesOutAtTheNextSweep() {
        standing.change(AUTHOR, "tc", null, null, new StandingVisas.OptionChange(false, false, true, false, null));
        when(github.appComment(eq(PR), any(), anyString())).thenThrow(HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "You have exceeded a secondary rate limit", HttpHeaders.EMPTY, new byte[0],
                StandardCharsets.UTF_8))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));

        standing.sweep();

        assertThat(standing.ghTokenRejected(AUTHOR)).isFalse();
        assertThat(standing.ghOn(AUTHOR)).isTrue();

        standing.sweep();
        standing.sweep();

        verify(github, times(2)).appComment(eq(PR), any(), contains("RunAll"));
        assertThat(standing.verdictCommentId(AUTHOR, PR, FIRST)).hasValue(555L);
    }

    /** JIRA answers 403 to a ticket the user may not comment on: the token, alive by /myself, stays. */
    @Test
    void aVisaJiraRefusesForAnotherReasonKeepsTheTokenAndIsTriedAgain() {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString())).thenThrow(status(403))
            .thenReturn(new JiraClient.PostedComment("18000002", "https://issues.example/browse/" + ISSUE));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()));

        standing.sweep();

        assertThat(standing.jiraTokenRejected(AUTHOR)).isFalse();
        assertThat(standing.visaOn(AUTHOR)).isTrue();
        assertThat(standing.postedCount()).isZero();

        standing.sweep();
        standing.sweep();

        verify(jira, times(2)).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
        assertThat(standing.postedCount()).isEqualTo(1);
    }

    /** ASF JIRA answered the dead token's comment with something else than 401: /myself tells. */
    @Test
    void aDeadJiraTokenIsFoundByAskingJiraWhoItIs() {
        visaOn();
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString())).thenThrow(status(404));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);

        standing.sweep();

        assertThat(standing.jiraTokenRejected(AUTHOR)).isTrue();
        assertThat(standing.visaOn(AUTHOR)).isFalse();
    }

    /** standing-visas.json of the release before has no held-back verdicts. */
    @Test
    void anOldSnapshotWithoutHeldVerdictsReads(@TempDir Path dir) throws Exception {
        visaOn();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).remove("owed");
        mapper.writeValue(file.toFile(), snap);
        StandingVisas restarted = standing();

        restarted.loadFrom(file);

        assertThat(restarted.visaOn(AUTHOR)).isTrue();
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
    }

    private void visaOn() {
        standing.change(AUTHOR, "tc", "jira-pat", null,
            new StandingVisas.OptionChange(true, false, false, false, null));
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, tc, github, analyzer, jira, visas, tracker, mock(Warmer.class),
            mock(PendingCommits.class));
    }

    private void finished(long buildId, String by) {
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(buildId, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(by)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict(buildId)));
    }

    private static HttpClientErrorException status(int code) {
        return HttpClientErrorException.create(HttpStatus.valueOf(code), HttpStatus.valueOf(code).getReasonPhrase(),
            HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private static AnalysisResult verdict(long buildId) {
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);

        return new AnalysisResult(PR, buildId, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
