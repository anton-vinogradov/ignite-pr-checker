package com.github.igniteprchecker.tc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The rerun tracker told the one-shot auto visa about every finished chain, a cancelled one included,
 * and the visa went out with the verdict of the clean chain before it. The tracker now says which
 * chain finished and whether it was cancelled; the subscriptions hear it through the application's
 * events, wired here the way the application wires them.
 */
class ChainFinishedEventTest {
    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";

    private final TcClient tc = mock(TcClient.class);

    private final Warmer warmer = mock(Warmer.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

    private RerunTracker tracker;

    @BeforeEach
    void setUp() {
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(SessionCodec.class, () -> new SessionCodec(new SessionProperties(false, "test-secret"),
            mapper));
        context.registerBean(AnalysisProperties.class, () -> new AnalysisProperties(null, RUN_ALL, null, null, null,
            null, null));
        context.registerBean(TcClient.class, () -> tc);
        context.registerBean(Warmer.class, () -> warmer);
        context.registerBean(BlockerAnalyzer.class, () -> analyzer);
        context.registerBean(GithubClient.class, () -> mock(GithubClient.class));
        context.registerBean(JiraClient.class, () -> mock(JiraClient.class));
        context.registerBean(VisaService.class, () -> mock(VisaService.class));
        context.registerBean(PendingCommits.class, () -> mock(PendingCommits.class));
        for (String pool : List.of("earlyRerunExecutor", "settleExecutor", "visaPosterExecutor"))
            context.registerBean(pool, ExecutorService.class, () -> Executors.newSingleThreadExecutor(),
                bd -> bd.setDestroyMethodName("shutdown"));
        context.register(RerunTracker.class, StandingVisas.class, VisaSubscriptions.class);
        context.refresh();

        tracker = context.getBean(RerunTracker.class);
        context.getBean(VisaSubscriptions.class).arm(PR, "IGNITE-28867", "jira-pat", "reviewer");
        when(warmer.borrowToken()).thenReturn("tc");
        when(tc.queuedBuilds("tc")).thenReturn(List.of());
        tracker.record(PR, chain("running", null));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void aCancelledChainPostsNoVisa() {
        when(tc.getChainDepStates("tc", CHAIN)).thenReturn(chain("finished", "UNKNOWN"));

        tracker.refresh();

        verify(analyzer, after(300).never()).forceRefresh(anyString(), anyInt());
    }

    @Test
    void aFinishedChainIsSettled() {
        when(tc.getChainDepStates("tc", CHAIN)).thenReturn(chain("finished", "FAILURE"));

        tracker.refresh();

        verify(tc, timeout(5_000)).buildTriggeredBy("tc", CHAIN);
        verify(analyzer, timeout(5_000)).forceRefresh("tc", PR);
    }

    private static TcModel.Build chain(String state, String status) {
        return new TcModel.Build(CHAIN, status, state, "pull/13335/head", RUN_ALL, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }
}
