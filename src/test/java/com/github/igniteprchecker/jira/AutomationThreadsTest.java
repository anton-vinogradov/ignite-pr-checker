package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.AnalysisConfig;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The early re-runs and the settles a finished chain or re-run asks for each ran on a thread their class made for
 * itself: nothing stopped them with the application, and AnalysisConfig, which tells where each kind of work runs,
 * could not name them. They are beans of AnalysisConfig now, stopped with it.
 */
class AutomationThreadsTest {
    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = AppWriting.github();

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final Warmer warmer = mock(Warmer.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** The thread each kind of work ran on. */
    private final Map<String, String> ranOn = new ConcurrentHashMap<>();

    @Test
    void automationRunsOnPoolsThatStopWithTheApplication() throws Exception {
        List<ExecutorService> pools;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.registerBean(SessionCodec.class, () -> new SessionCodec(new SessionProperties(false, "test-secret"),
                mapper));
            context.registerBean(AnalysisProperties.class, () -> new AnalysisProperties(null, "RunAll", null, null,
                null, null, null));
            context.registerBean(TcClient.class, () -> tc);
            context.registerBean(GithubClient.class, () -> github);
            context.registerBean(BlockerAnalyzer.class, () -> analyzer);
            context.registerBean(Warmer.class, () -> warmer);
            context.registerBean(RerunTracker.class, () -> mock(RerunTracker.class));
            context.registerBean(JiraClient.class, () -> mock(JiraClient.class));
            context.registerBean(VisaService.class, () -> mock(VisaService.class));
            context.registerBean(PendingCommits.class, () -> mock(PendingCommits.class));
            context.register(AnalysisConfig.class, StandingVisas.class, SettingsJournal.class);
            context.refresh();

            Map<String, ExecutorService> beans = context.getBeansOfType(ExecutorService.class);
            assertThat(beans).containsKeys("earlyRerunExecutor", "settleExecutor");
            pools = List.of(beans.get("earlyRerunExecutor"), beans.get("settleExecutor"));

            StandingVisas standing = context.getBean(StandingVisas.class);
            when(github.ghIdentity("gh-pat")).thenReturn(Optional.of(GhUsers.of("alice-gh")));
            standing.change("alice", "tc", null, "gh-pat", new StandingVisas.OptionChange(false, true, true, false, null));
            when(tc.buildTriggeredBy(anyString(), anyLong())).thenAnswer(inv -> ran("early re-run", Optional.empty()));
            when(github.openPrs()).thenReturn(List.of());
            when(github.pullState(PR)).thenAnswer(inv -> ran("settle", Optional.empty()));

            context.publishEvent(new RerunTracker.SuiteFailedMidRun(PR, CHAIN, "Cache1", 9389100L, "Cache 1"));
            standing.settleSoon(PR);

            for (ExecutorService pool : pools)
                pool.submit(() -> { }).get(5, TimeUnit.SECONDS);
        }

        assertThat(ranOn).containsEntry("early re-run", "early-rerun-1").containsEntry("settle", "settle-1");
        assertThat(pools).allMatch(ExecutorService::isShutdown);
    }

    private <T> T ran(String work, T answer) {
        ranOn.putIfAbsent(work, Thread.currentThread().getName());

        return answer;
    }
}
