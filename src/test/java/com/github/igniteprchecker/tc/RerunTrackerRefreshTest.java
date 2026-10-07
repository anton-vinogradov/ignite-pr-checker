package com.github.igniteprchecker.tc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * The rerun tracker's poll is what starts early re-runs, settles finished chains and warms their verdicts, yet every
 * test replaced it with a mock, while most fixes of the last months went into exactly that path. Here it runs on
 * answers TeamCity gave for RunAll 9389046 of apache/ignite#13335: a suite failing mid-chain, the chain finishing, a
 * build TeamCity no longer has.
 */
class RerunTrackerRefreshTest {
    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final long CACHE1 = 9389100L;

    private static final long RERUN = 9389500L;

    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";

    private final TcClient tc = mock(TcClient.class);

    private final Warmer warmer = mock(Warmer.class);

    private final List<Object> events = new ArrayList<>();

    private final RerunTracker tracker = new RerunTracker(tc, warmer,
        new AnalysisProperties(null, RUN_ALL, null, null, null, null, null), new ObjectMapper(), events::add);

    @BeforeEach
    void setUp() {
        when(warmer.borrowToken()).thenReturn("tc");
        when(tc.queuedBuilds("tc")).thenReturn(List.of());
    }

    @Test
    void aSuiteFailingMidChainIsAnnouncedOnce() {
        tracker.record(PR, build(CHAIN, RUN_ALL, "running", null, null));
        when(tc.getChainDepStates("tc", CHAIN)).thenReturn(build(CHAIN, RUN_ALL, "running", null,
            new TcModel.SnapshotDeps(2, List.of(build(CACHE1, "IgniteTests24Java8_Cache1", "finished", "FAILURE", null),
                build(CACHE1 + 1, "IgniteTests24Java8_Cache2", "running", null, null)))));

        tracker.refresh();
        tracker.refresh();

        assertThat(events).containsExactly(new RerunTracker.SuiteFailedMidRun(PR, CHAIN, "IgniteTests24Java8_Cache1",
            CACHE1, "Cache 1"));
        assertThat(tracker.newestChainUnderWay(PR)).isEqualTo(CHAIN);
        assertThat(tracker.active()).extracting(RerunTracker.ActiveRerun::buildId).containsExactly(CHAIN, CACHE1 + 1);
    }

    @Test
    void aFinishedChainIsWarmedAndAnnouncedAndLeavesTheTracker() {
        tracker.record(PR, build(CHAIN, RUN_ALL, "running", null, null));
        when(tc.getChainDepStates("tc", CHAIN)).thenReturn(build(CHAIN, RUN_ALL, "finished", "FAILURE", null));

        tracker.refresh();

        verify(warmer).warmPr(PR);
        assertThat(events).containsExactly(new RerunTracker.ChainFinished(PR, CHAIN, false));
        assertThat(tracker.tracks(CHAIN)).isFalse();
        assertThat(tracker.newestChainUnderWay(PR)).isZero();
    }

    @Test
    void aFinishedReRunRecomputesTheVerdict() {
        tracker.record(PR, build(RERUN, "IgniteTests24Java8_Cache1", "queued", null, null));
        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "IgniteTests24Java8_Cache1", "finished", "SUCCESS",
            null));

        tracker.refresh();

        verify(warmer).refreshPr(PR);
        assertThat(events).containsExactly(new RerunTracker.RerunFinished(PR, RERUN));
        assertThat(tracker.hasActive(PR)).isFalse();
    }

    @Test
    void aBuildTeamCityNoLongerHasIsDropped() {
        tracker.record(PR, build(RERUN, "IgniteTests24Java8_Cache1", "queued", null, null));
        when(tc.getBuildState("tc", RERUN)).thenThrow(HttpClientErrorException.create(HttpStatusCode.valueOf(404),
            "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        tracker.refresh();

        assertThat(tracker.tracks(RERUN)).isFalse();
        assertThat(events).isEmpty();
        verify(warmer, never()).refreshPr(PR);
    }

    @Test
    void aTeamCityErrorKeepsTheBuildForTheNextLook() {
        tracker.record(PR, build(RERUN, "IgniteTests24Java8_Cache1", "queued", null, null));
        when(tc.getBuildState("tc", RERUN)).thenThrow(HttpServerErrorException.create(HttpStatusCode.valueOf(502),
            "Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        tracker.refresh();

        assertThat(tracker.tracks(RERUN)).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    void withoutATokenNothingIsAsked() {
        when(warmer.borrowToken()).thenReturn(null);
        tracker.record(PR, build(CHAIN, RUN_ALL, "running", null, null));

        tracker.refresh();

        verify(tc, never()).getChainDepStates("tc", CHAIN);
        assertThat(tracker.tracks(CHAIN)).isTrue();
    }

    private static TcModel.Build build(long id, String type, String state, String status, TcModel.SnapshotDeps deps) {
        String name = type.equals(RUN_ALL) ? "RunAll" : "Cache " + type.charAt(type.length() - 1);

        return new TcModel.Build(id, status, state, "pull/13335/head", type, "https://ci2.example/build/" + id, null,
            null, null, null, null, new TcModel.BuildType(type, name), null, deps, null, null, null, null);
    }
}
