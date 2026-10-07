package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.CancelledSuite;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * PR 13592: TeamCity itself cancelled four suites of the RunAll with "Build revision not found", and the
 * auto re-run, which took only blocker, watch and broken suites, left them unrun. A suite a person
 * cancelled was meant not to run and stays out of the wave, and so does one TeamCity said nothing about.
 */
class CancelledSuitesWaveTest {
    private static final String USER = "avinogradov";

    private static final String TOK = "tc";

    private static final int PR = 13592;

    private static final long RUN_ALL = 9392000L;

    private final ObjectMapper mapper = new ObjectMapper();

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final long now = System.currentTimeMillis() / 1000;

    @Test
    void suitesTeamCityCancelledGoIntoTheWaveAndThoseAPersonCancelledDoNot(@TempDir Path dir) throws Exception {
        enrolledBeforeTheRun(dir);
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28890 Fix it", null, null, null, null)));
        when(tc.findRunAllBuildForPr(TOK, PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE", "finished",
            "pull/13592/head", null, null, null, null, TcDates.format(now - 600), null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        when(analyzer.analyzeForAction(TOK, PR)).thenReturn(Optional.of(new AnalysisResult(PR, RUN_ALL,
            "pull/13592/head", System.currentTimeMillis(), List.of(), List.of(), List.of(), List.of(), List.of(), 140,
            0, true, 4, false, 0, 0, 0, now - 600, now - 900, List.of(), List.of(
                new CancelledSuite("IgniteTests24Java8_Cache1", 9392010L, "Cache 1", "Build revision not found", null,
                    true),
                new CancelledSuite("IgniteTests24Java8_Cache2", 9392011L, "Cache 2", "Build revision not found", null,
                    true),
                new CancelledSuite("IgniteTests24Java8_Queries1", 9392012L, "Queries 1", "Not needed", USER, false),
                new CancelledSuite("IgniteTests24Java8_Queries2", 9392013L, "Queries 2", null, null, false)),
            List.of(), 0)));
        when(tc.triggerBuildReplacingQueued(eq(TOK), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenAnswer(inv -> new TcModel.Build(9392600L, null, "queued", null, inv.getArgument(1), null, null, null,
                null, null, null, null, null, null, null, null, null, null));
        when(tc.getBuildState(eq(TOK), anyLong())).thenReturn(new TcModel.Build(9392600L, null, "queued", null, null,
            null, null, null, null, null, TcDates.format(now + 1800), null, null, null, null, null, null, null));

        standing.sweep();

        ArgumentCaptor<String> suites = ArgumentCaptor.forClass(String.class);
        verify(tc, atLeastOnce()).triggerBuildReplacingQueued(eq(TOK), suites.capture(), eq(PR), anyBoolean(),
            anyString());
        assertThat(suites.getAllValues()).containsExactlyInAnyOrder("IgniteTests24Java8_Cache1",
            "IgniteTests24Java8_Cache2");
        assertThat(standing.waveStatus(PR, RUN_ALL)).get().extracting(StandingVisas.WaveStatus::what)
            .isEqualTo("2 cancelled suite(s)");
    }

    /** Restores the user, auto re-run on, from a snapshot taken a day before the run. */
    private void enrolledBeforeTheRun(Path dir) throws Exception {
        standing.enable(USER, TOK, null, null, false, true, false, false);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) snap.get("enrollments").get(0)).put("enabledAt", (now - 86_400) * 1000);
        mapper.writeValue(file.toFile(), snap);
        standing.loadFrom(file);
    }
}
