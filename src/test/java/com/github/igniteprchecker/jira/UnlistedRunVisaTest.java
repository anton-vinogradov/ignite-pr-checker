package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * PR 13335 was open, but not among the 50 most recently updated PRs the sweep lists. avinogradov, with Visa my runs in
 * JIRA on and no auto re-run, started its RunAll from the TeamCity UI, and nobody had the PR page open while it ran.
 * The run finished, the PR page said "Visa: will be posted to IGNITE-28867 when the run settles", and no visa came
 * until something on GitHub moved the PR back into the list. TeamCity already named the PR among the chains finished
 * since the last sweep; the sweep now settles such a PR when the user who started the run gets a visa or a verdict
 * comment from it.
 */
class UnlistedRunVisaTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = started();

    private final long finished = System.currentTimeMillis() + 60_000;

    @BeforeEach
    void setUp() {
        when(github.openPrs()).thenReturn(List.of(new PrSummary(13999, "IGNITE-29000 something else", null, null,
            null, null)));
        when(github.pullState(PR)).thenReturn(Optional.of(new GithubClient.PullState(
            ISSUE + " Hot reload of SSL certificates", true, false)));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "SUCCESS",
            "finished", "pull/13335/head", null, null, null, null, TcDates.format(finished / 1000), null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.buildRevision(eq("tc"), anyLong())).thenReturn(Optional.of("9f1c2e7a"));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL)));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "u"));
    }

    @Test
    void theRunOfAPrOutOfTheListGetsItsVisa() {
        visaOn();
        finishedAtTheSecondSweep(Map.of(PR, Set.of(USER)));

        for (int i = 0; i < 4; i++)
            standing.sweep();

        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
        verify(tc, times(1)).findRunAllBuildForPr("tc", PR);
        assertThat(standing.visaLine(USER, PR, RUN_ALL, () -> new StandingVisas.Run(USER, false, finished), false)
            .state()).isEqualTo(StandingVisas.VisaLine.POSTED);
    }

    /** The verdict comment of the PR comes the same way. */
    @Test
    void theRunOfAPrOutOfTheListGetsItsVerdictComment() {
        standing.change(USER, "tc", null, null, new StandingVisas.OptionChange(false, false, true, false, null));
        when(github.appComment(eq(PR), anyString(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        finishedAtTheSecondSweep(Map.of(PR, Set.of(USER)));

        for (int i = 0; i < 3; i++)
            standing.sweep();

        verify(github).appComment(eq(PR), anyString(), anyString());
    }

    /**
     * A run TeamCity queued by itself, a run of a user the checker does not know and a run of a user who has only auto
     * re-run on owe no visa and no comment: the sweep does not look these PRs up.
     */
    @Test
    void aRunThatOwesNoVisaNorCommentIsNotLookedUp() {
        standing.change("zstan", "tc-zstan", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        finishedAtTheSecondSweep(Map.of(PR, Set.of(), 13400, Set.of("stranger"), 13401, Set.of("zstan")));

        for (int i = 0; i < 3; i++)
            standing.sweep();

        verify(tc, never()).findRunAllBuildForPr(anyString(), eq(PR));
        verify(tc, never()).findRunAllBuildForPr(anyString(), eq(13400));
        verify(tc, never()).findRunAllBuildForPr(anyString(), eq(13401));
        verify(github, never()).pullState(anyInt());
    }

    /** Right after the chain finished, the analysis still named the run before it: the next sweep settles the PR. */
    @Test
    void aSettleThatStoppedShortIsTriedAgain() {
        visaOn();
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL - 900)),
            Optional.of(verdict(RUN_ALL)));
        when(analyzer.forceRefresh("tc", PR)).thenReturn(Optional.of(verdict(RUN_ALL - 900)));
        finishedAtTheSecondSweep(Map.of(PR, Set.of(USER)));

        for (int i = 0; i < 4; i++)
            standing.sweep();

        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
        verify(tc, times(2)).findRunAllBuildForPr("tc", PR);
    }

    /** The hourly sweep that looks every listed PR up hears of the run too. */
    @Test
    void theSweepThatLooksEveryPrUpHearsOfTheRun() {
        visaOn();
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()),
            Optional.of(Map.of()), Optional.of(Map.of()), Optional.of(Map.of()), Optional.of(Map.of()),
            Optional.of(Map.of()), Optional.of(Map.of(PR, Set.of(USER))));

        for (int i = 0; i < 7; i++)
            standing.sweep();

        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
    }

    /**
     * A release restarted the service. The RunAll had finished 10 minutes before the start, after the last sweep before
     * the stop: of the sweeps after the start, only the first one can hear of it.
     */
    @Test
    void aRunThatFinishedWhileTheServiceWasStoppedGetsItsVisa(@TempDir Path dir) throws IOException {
        visaOn();
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        long started = System.currentTimeMillis();
        switchedOnAt(file, started - 3_600_000);
        StandingVisas restarted = started();
        restarted.loadFrom(file);
        long finishedMeanwhile = started - 600_000;
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "SUCCESS",
            "finished", "pull/13335/head", null, null, null, null, TcDates.format(finishedMeanwhile / 1000), null, null,
            null, new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenAnswer(ask -> ask.<Long>getArgument(1) * 1000
            < finishedMeanwhile ? Optional.of(Map.of(PR, Set.of(USER))) : Optional.of(Map.of()));

        for (int i = 0; i < 3; i++)
            restarted.sweep();

        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString());
    }

    /** The service as it starts, with the same TeamCity, GitHub and JIRA. */
    private StandingVisas started() {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc,
            github, analyzer, jira, new VisaService(new TeamcityProperties("https://ci2.example/"),
            "https://checker.example"), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }

    /** Moves when the options in the state {@code file} were switched on to {@code at}. */
    private void switchedOnAt(Path file, long at) throws IOException {
        ObjectNode state = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode enrollment = (ObjectNode)state.get("enrollments").get(0);
        enrollment.put("enabledAt", at);
        ObjectNode onSince = (ObjectNode)enrollment.get("onSince");
        onSince.propertyNames().forEach(option -> onSince.put(option, at));
        mapper.writeValue(file.toFile(), state);
    }

    private void visaOn() {
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, false, false, false, null));
    }

    /** The second sweep hears of {@code prs}, the others of nothing. */
    private void finishedAtTheSecondSweep(Map<Integer, Set<String>> prs) {
        when(tc.prsWithChainsFinishedAfter(anyString(), anyLong())).thenReturn(Optional.of(Map.of()), Optional.of(prs),
            Optional.of(Map.of()));
    }

    private static AnalysisResult verdict(long buildId) {
        return new AnalysisResult(PR, buildId, "pull/13335/head", System.currentTimeMillis(), List.of(), List.of(),
            List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
