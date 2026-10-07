package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Three suites of one chain re-run while it was still going were told as three waves: "Auto re-run
 * #3 — 1 suite that failed mid-run" and then "#4 (attempt 2/2)", where up to 2 waves were promised.
 * And a red verdict ended on the list of blockers, with no word on what to do next.
 */
class RerunWavesTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, analyzer,
        mock(JiraClient.class), new VisaService(new TeamcityProperties("https://ci2.example/"),
            "https://checker.example"), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));

    private final AnalysisResult verdict = verdict(blocker("Cache1", 9389011L), blocker("Cache2", 9389012L),
        blocker("Queries5", 9389013L));

    @BeforeEach
    void setUp() {
        when(github.ghUser("gh-pat")).thenReturn(Optional.of("anton-vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(github.addPrComment(eq("gh-pat"), eq(PR), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(tc.buildTriggeredBy("tc", RUN_ALL)).thenReturn(Optional.of(USER));
        when(tc.triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        when(tc.getBuildState(anyString(), anyLong())).thenReturn(new TcModel.Build(9389100L, null, "queued", null,
            null, null, null, null, null, null, "20991231T000000+0000", null, null, null, null, null, null, null));
        when(analyzer.analyze("tc", PR)).thenReturn(Optional.of(verdict));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict));
    }

    @Test
    void suitesReRunWhileTheChainRanAreOneWave() {
        options(true);

        failMidRun();

        assertThat(standing.waveStatus(PR, RUN_ALL)).hasValueSatisfying(w -> {
            assertThat(w.wave()).isEqualTo(1);
            assertThat(w.what()).isEqualTo("3 suites that failed mid-run");
        });
    }

    /** TeamCity refused to queue the re-run of Cache 1; Cache 2 and Queries 5 went in. */
    @Test
    void aSuiteThatWasNotReQueuedIsNotCounted() {
        options(true);
        when(tc.triggerBuildReplacingQueued(anyString(), eq("Cache1"), eq(PR), anyBoolean(), anyString()))
            .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        for (TestVerdict v : verdict.blockers()) {
            try {
                standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, RUN_ALL, v.suite(), v.suiteBuildId(),
                    v.suiteName()));
            }
            catch (HttpServerErrorException e) {
                assertThat(v.suite()).isEqualTo("Cache1");
            }
        }

        assertThat(standing.waveStatus(PR, RUN_ALL)).hasValueSatisfying(
            w -> assertThat(w.what()).isEqualTo("2 suites that failed mid-run"));
    }

    @Test
    void theWaveAfterTheChainIsTheSecondOfTwo() {
        options(true);
        failMidRun();
        finished();

        standing.sweep();

        assertThat(standing.waveStatus(PR, RUN_ALL)).hasValueSatisfying(w -> {
            assertThat(w.wave()).isEqualTo(2);
            assertThat(w.what()).isEqualTo("3 blocker suite(s)");
        });
        assertThat(standing.phase(PR, RUN_ALL).wave()).isEqualTo(2);
    }

    /** A chain was mid-run during the upgrade: its waves were kept one per suite. */
    @Test
    void mergesTheMidRunWavesOfAnOlderSnapshot(@TempDir Path dir) throws Exception {
        options(true);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        snap.putObject("retries").putObject(String.valueOf(PR)).put("buildId", RUN_ALL).put("attempts", 1)
            .put("what", "1 suite that failed mid-run").putNull("note").putArray("history").add("early: Cache 1")
            .add("early: Cache 2").add("early: Queries 5");
        mapper.writeValue(file.toFile(), snap);

        standing.loadFrom(file);

        assertThat(standing.waveStatus(PR, RUN_ALL)).hasValueSatisfying(w -> {
            assertThat(w.wave()).isEqualTo(1);
            assertThat(w.what()).isEqualTo("3 suites that failed mid-run");
        });
    }

    @Test
    void aRedVerdictEndsWithTheNextStep() {
        options(false);
        finished();

        standing.sweep();

        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(github).addPrComment(eq("gh-pat"), eq(PR), comment.capture());
        assertThat(comment.getValue()).endsWith("➡️ **Next:** fix the blockers, push, and run RunAll again — a "
            + "`/run-all` comment here does it when PR commands are on in the checker's ⚙.");
    }

    @Test
    void aCleanVerdictHasNoNextStep() {
        options(false);
        finished();
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));

        standing.sweep();

        ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
        verify(github).addPrComment(eq("gh-pat"), eq(PR), comment.capture());
        assertThat(comment.getValue()).doesNotContain("Next:");
    }

    private void options(boolean rerun) {
        standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(false, rerun, true, false, null));
    }

    private void failMidRun() {
        for (TestVerdict v : verdict.blockers())
            standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, RUN_ALL, v.suite(), v.suiteBuildId(),
                v.suiteName()));
    }

    private void finished() {
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
    }

    private static TestVerdict blocker(String suite, long suiteBuildId) {
        return new TestVerdict(suiteBuildId, suite + "Test.test", suite, suiteBuildId, suite.replaceAll("(\\d)", " $1"),
            "o" + suiteBuildId, true, false, "not seen failing in 100 master run(s)", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... blockers) {
        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 0, 0);
    }
}
