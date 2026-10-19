package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.github.igniteprchecker.persist.TokenScrub;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A copy of the state older than two days holds no tokens. Restored, it cost the users their options: the next
 * finished run of a user with the visa on dropped all their options for want of the JIRA token, and the command poll
 * switched the visa and the options that commit off as if JIRA and GitHub had refused the tokens. Restores the options
 * of PR 13335's author and of a user who adds tests to suites from such a copy.
 */
class RestoredWithoutTokensTest {
    private static final String AUTHOR = "avinogradov";

    private static final String FIXER = "dave";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private Path live;

    private Path copy;

    private StandingVisas restored;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        when(jira.myself(anyString())).thenReturn(Optional.of("Anton Vinogradov"));
        when(jira.addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/" + ISSUE));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.tokenOwner("dave-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("dave-gh"),
            null)));

        StandingVisas before = standing();
        before.change(AUTHOR, "author-tc", "jira-pat", null,
            new StandingVisas.OptionChange(true, false, false, false, null));
        before.change(FIXER, "dave-tc", null, "dave-pat", new StandingVisas.OptionChange(false, true, false, null,
            null, true));
        live = dir.resolve("standing-visas.json");
        before.saveTo(live);
        copy = Files.copy(live, dir.resolve("standing-visas.json.before-1.35.0-built-20261010-120000"));
        TokenScrub.removeFrom(copy, System.currentTimeMillis());

        restored = standing();
        restored.loadFrom(copy);
    }

    @Test
    void theCopyHoldsNoneOfTheirTokens() throws IOException {
        String left = Files.readString(copy);

        for (JsonNode e : mapper.readTree(live.toFile()).get("enrollments")) {
            for (String field : List.of("tcToken", "jiraToken", "ghToken")) {
                if (!e.get(field).isNull())
                    assertThat(left).as(field + " of " + e.get("username")).doesNotContain(e.get(field).asText());
            }
        }
        assertThat(left).contains("\"username\":\"" + AUTHOR + "\"", "\"username\":\"" + FIXER + "\"");
    }

    @Test
    void theOptionsStayOnAndSettingsSayWhichTokenToSaveAgain() {
        restored.ensureGhLogins();

        assertThat(restored.visaOn(AUTHOR)).isTrue();
        assertThat(restored.settings(AUTHOR).tokensRemoved()).containsExactly("jira");
        assertThat(restored.settings(AUTHOR).jiraTokenRejected()).as("JIRA refused nothing").isFalse();
        assertThat(restored.settings(FIXER).suites()).isTrue();
        assertThat(restored.settings(FIXER).tokensRemoved()).containsExactly("github");
        assertThat(restored.settings(FIXER).ghTokenRejected()).as("GitHub refused nothing").isFalse();
        assertThat(restored.suiteFixers()).as("paused until the token is saved").isEmpty();
    }

    /** Another user's visit gives the sweep a TeamCity token to look with; the author has not come back yet. */
    @Test
    void aRunThatFinishesBeforeItsUserComesBackWaitsForThem() {
        restored.tcTokenOffered(FIXER, "dave-tc");
        finishedRunOf(AUTHOR);

        restored.sweep();

        assertThat(restored.enabled(AUTHOR)).isTrue();
        assertThat(restored.settings(AUTHOR).tcTokenRejected()).as("TeamCity refused nothing").isFalse();
        verify(analyzer, never()).analyzeForAction(eq("author-tc"), eq(PR));
    }

    @Test
    void aRunThatFinishesBeforeTheJiraTokenIsSavedDropsNothing() {
        restored.tcTokenOffered(AUTHOR, "author-tc");
        finishedRunOf(AUTHOR);

        restored.sweep();

        assertThat(restored.enabled(AUTHOR)).isTrue();
        assertThat(restored.visaOn(AUTHOR)).isTrue();
        verify(jira, never()).addCommentWithId(any(), any(), any());
    }

    @Test
    void theVisaHeldBackGoesOutOnceTheJiraTokenIsSavedAgain() {
        restored.tcTokenOffered(AUTHOR, "author-tc");
        finishedRunOf(AUTHOR);
        restored.sweep();

        restored.change(AUTHOR, "author-tc", "fresh-jira-pat", null,
            new StandingVisas.OptionChange(true, null, null, null, null));

        verify(jira, timeout(5_000)).addCommentWithId(eq("fresh-jira-pat"), eq(ISSUE), contains("RunAll"));
        assertThat(restored.settings(AUTHOR).tokensRemoved()).isEmpty();
    }

    /**
     * A switch of any other option asked for the missing token first: the author could not switch the auto re-run on
     * without the JIRA token, nor dave switch it off without the GitHub token. The options that wait for a token still
     * do, and switching the visa on again still asks for it.
     */
    @Test
    void theOtherSwitchesWorkWhileATokenIsAwaited() {
        Optional<StandingVisas.Refusal> author = restored.change(AUTHOR, "author-tc", null, null,
            new StandingVisas.OptionChange(null, true, null, null, null));
        Optional<StandingVisas.Refusal> fixer = restored.change(FIXER, "dave-tc", null, null,
            new StandingVisas.OptionChange(null, false, null, null, null));

        assertThat(author).isEmpty();
        assertThat(fixer).isEmpty();
        assertThat(restored.settings(AUTHOR).rerun()).isTrue();
        assertThat(restored.settings(AUTHOR).tokensRemoved()).containsExactly("jira");
        assertThat(restored.settings(FIXER).rerun()).isFalse();
        assertThat(restored.settings(FIXER).suites()).isTrue();
        assertThat(restored.settings(FIXER).tokensRemoved()).containsExactly("github");
        assertThat(restored.change(AUTHOR, "author-tc", null, null,
            new StandingVisas.OptionChange(true, null, null, null, null)))
            .contains(new StandingVisas.Refusal("jira", "Visa my runs in JIRA needs your JIRA token"));
    }

    /**
     * The line under the verdict said "Visa my runs in JIRA is off in ⚙" of a visa that waits for the JIRA token, and
     * "JIRA did not take it" once the run settled. The author is told to save the token; another viewer, only that the
     * author's option is on.
     */
    @Test
    void theVisaLineSaysTheVisaWaitsForTheJiraToken() {
        StandingVisas.Run going = new StandingVisas.Run(AUTHOR, false, 0);
        restored.tcTokenOffered(AUTHOR, "author-tc");

        StandingVisas.VisaLine before = restored.visaLine(AUTHOR, PR, RUN_ALL, () -> going, false);
        finishedRunOf(AUTHOR);
        restored.sweep();
        StandingVisas.VisaLine settled = restored.visaLine(AUTHOR, PR, RUN_ALL,
            () -> new StandingVisas.Run(AUTHOR, false, System.currentTimeMillis()), false);

        assertThat(before.state()).isEqualTo(StandingVisas.VisaLine.JIRA_TOKEN_REMOVED);
        assertThat(settled.state()).as("the visa stays owed").isEqualTo(StandingVisas.VisaLine.JIRA_TOKEN_REMOVED);
        assertThat(restored.visaLine(FIXER, PR, RUN_ALL, () -> going, false))
            .isEqualTo(new StandingVisas.VisaLine(StandingVisas.VisaLine.ON, AUTHOR, false, null, null, false));
    }

    @Test
    void theMarksOutliveARestart(@TempDir Path dir) throws IOException {
        Path again = dir.resolve("standing-visas.json");
        restored.saveTo(again);
        StandingVisas restarted = standing();

        restarted.loadFrom(again);

        assertThat(restarted.settings(AUTHOR).tokensRemoved()).containsExactly("jira");
        assertThat(restarted.settings(FIXER).tokensRemoved()).containsExactly("github");
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, tc, github, analyzer, jira,
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"),
            mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }

    private void finishedRunOf(String user) {
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(user)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict()));
    }

    private static AnalysisResult verdict() {
        TestVerdict blocker = new TestVerdict(9389011L, "org.apache.ignite.CacheTest.testPut", "Cache1", 9389011L,
            "Cache 1", "o1", true, false, "not seen failing in 100 master runs", "FF", 2);

        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
