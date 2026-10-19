package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * standing-visas.json keeps each auto re-run wave's label as the release that queued it wrote it: "1 blocker
 * suite(s)". A wave queued before a deploy kept those words after it, on the page's settling line and in the settled
 * line of the PR comment, next to the words of the waves queued after the deploy.
 */
class EarlierWaveLabelsTest {
    private static final String USER = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private static final String ISSUE = "IGNITE-28867";

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final RerunTracker tracker = mock(RerunTracker.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = standing();

    @BeforeEach
    void setUp() {
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, ISSUE + " Hot reload of SSL certificates", null,
            null, null, null)));
        when(github.appComment(eq(PR), any(), anyString()))
            .thenReturn(new GithubClient.PostedComment(555L, "https://github.com/apache/ignite/pull/13335#c555"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/browse/" + ISSUE));
        when(tc.triggerBuildReplacingQueued(eq("tc"), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(new TcModel.Build(9389100L, null, "queued", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null));
        when(tc.buildRevision(eq("tc"), anyLong())).thenReturn(Optional.of("9f1c2e7a"));
        when(tc.findRunAllBuildForPr("tc", PR)).thenReturn(Optional.of(new TcModel.Build(RUN_ALL, "FAILURE",
            "finished", "pull/13335/head", null, null, null, null, null, null, null, null,
            new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)));
        standing.change(USER, "tc", "jira-pat", null, new StandingVisas.OptionChange(true, true, true, false, null));
        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict(blocker())));
        standing.sweep();
    }

    @Test
    void aWaveQueuedBeforeTheDeploySettlesInTheNewWords(@TempDir Path dir) throws Exception {
        StandingVisas deployed = deployedWith(dir, "1 blocker suite(s)");

        assertThat(deployed.phase(PR, RUN_ALL).what()).isEqualTo("1 suite with blockers");
        assertThat(deployed.waveStatus(PR, RUN_ALL).orElseThrow().what()).isEqualTo("1 suite with blockers");

        when(analyzer.analyzeForAction("tc", PR)).thenReturn(Optional.of(verdict()));
        deployed.sweep();
        deployed.sweep();

        verify(github).appComment(eq(PR), any(), contains("Settled after 1 auto re-run wave: #1 — 1 suite with "
            + "blockers."));
    }

    @Test
    void everyEarlierLabelIsSaidInTheNewWords(@TempDir Path dir) throws Exception {
        Map<String, String> labels = Map.of(
            "2 blocker suite(s)", "2 suites with blockers",
            "1 watch suite(s)", "1 suite with tests that started failing",
            "3 broken suite(s)", "3 broken suites",
            "4 cancelled suite(s)", "4 cancelled suites",
            "2 blocker + 1 broken suite(s)", "3 suites (2 with blockers, 1 broken)",
            "2 blocker + 1 watch + 3 broken + 4 cancelled suite(s)",
            "10 suites (2 with blockers, 1 with tests that started failing, 3 broken, 4 cancelled)",
            "0 suite(s)", "0 suites",
            "3 suites that failed mid-run", "3 suites that failed mid-run");

        saidAs(dir, labels);
    }

    /**
     * The release before this one said "1 suite to watch" of a suite re-run for a test under Recently started failing,
     * the page's name for it. A label without those words stays as it is.
     */
    @Test
    void aSuiteToWatchIsSaidInThePagesWords(@TempDir Path dir) throws Exception {
        saidAs(dir, Map.of(
            "1 suite to watch", "1 suite with tests that started failing",
            "2 suites to watch", "2 suites with tests that started failing",
            "4 suites (1 with blockers, 2 to watch, 1 broken)",
            "4 suites (1 with blockers, 2 with tests that started failing, 1 broken)",
            "2 suites with blockers", "2 suites with blockers",
            "3 suites (2 with blockers, 1 broken)", "3 suites (2 with blockers, 1 broken)"));
    }

    /** Each label of a wave queued before the deploy, the key, reads after it as its value. */
    private void saidAs(Path dir, Map<String, String> labels) {
        labels.forEach((earlier, now) -> {
            try {
                assertThat(deployedWith(dir, earlier).phase(PR, RUN_ALL).what()).as(earlier).isEqualTo(now);
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    /** The service after a deploy, from the snapshot of the wave queued before it, labelled {@code label}. */
    private StandingVisas deployedWith(Path dir, String label) throws Exception {
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        for (ObjectNode wave : List.of((ObjectNode)snap.get("waves").get(String.valueOf(RUN_ALL)),
            (ObjectNode)snap.get("retries").get(String.valueOf(PR)))) {
            wave.put("what", label);
            wave.putArray("history").add(label);
        }
        mapper.writeValue(file.toFile(), snap);

        StandingVisas deployed = standing();
        deployed.loadFrom(file);

        return deployed;
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc,
            github, analyzer, jira,
            new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example"), tracker,
            mock(Warmer.class), mock(PendingCommits.class));
    }

    private static TestVerdict blocker() {
        return new TestVerdict(9389011L, "Cache1Test.test", "Cache1", 9389011L, "Cache 1", "o1", true, false,
            "not seen failing in 100 master runs", "F", 1);
    }

    private static AnalysisResult verdict(TestVerdict... blockers) {
        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blockers),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 1, 0);
    }
}
