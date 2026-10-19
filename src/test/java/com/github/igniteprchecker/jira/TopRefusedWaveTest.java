package com.github.igniteprchecker.jira;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto re-run asks for the top of the queue, which ci2 lets only some accounts reorder, and TeamCity queues a build
 * before it moves it. For anyone else the re-run was queued at the end, TeamCity answered 403, and the checker counted
 * nothing: the next announcement or sweep queued the same suite again, each ten minutes, and the verdict waited for a
 * wave that never counted. On PR 13335 alice, who may not reorder ci2's queue, has auto re-run on.
 */
class TopRefusedWaveTest {
    private static final String USER = "alice";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String CACHE1 = "IgniteTests24Java8_Cache1";

    /** Every build TeamCity was asked to queue, by build type. */
    private final List<String> queued = new CopyOnWriteArrayList<>();

    private final List<String> waiting = new CopyOnWriteArrayList<>();

    private final AtomicLong nextId = new AtomicLong(9389100L);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final GithubClient github = mock(GithubClient.class);

    private HttpServer server;

    private TcClient tc;

    private StandingVisas standing;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();

        tc = spy(new TcClient(new TeamcityProperties("http://" + server.getAddress().getHostString() + ":"
            + server.getAddress().getPort() + "/"), new AnalysisProperties(null, "RunAll", null, null, null, null, null),
            new Metrics(Jackson2Defaults.mapper())));
        doReturn(Optional.of(USER)).when(tc).buildTriggeredBy(anyString(), eq(CHAIN));
        doReturn(Optional.empty()).when(tc).prsWithChainsFinishedAfter(anyString(), anyLong());
        doReturn(new TcModel.Build(1L, null, "queued", null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null)).when(tc).getBuildState(anyString(), anyLong());

        ObjectMapper mapper = Jackson2Defaults.mapper();
        standing = new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc,
            github, analyzer, mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class));
        standing.change(USER, "alice-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));

        AnalysisResult verdict = new AnalysisResult(PR, CHAIN, "pull/13335/head", System.currentTimeMillis(),
            List.of(new TestVerdict(9389011L, "CacheTest.test", CACHE1, 9389011L, "Cache 1", "o1", true, false,
                "not seen failing in 100 master runs", "F", 1)),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 0, 0);
        when(analyzer.analyze(anyString(), eq(PR))).thenReturn(Optional.of(verdict));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void anEarlyReRunQueuedAtTheEndIsOneReRun() {
        RerunTracker.SuiteFailedMidRun failed = new RerunTracker.SuiteFailedMidRun(PR, CHAIN, CACHE1, 9389011L,
            "Cache 1");

        standing.earlyRerun(failed);
        standing.earlyRerun(failed);

        assertThat(queued).containsExactly(CACHE1);
        assertThat(standing.waveStatus(PR, CHAIN)).hasValueSatisfying(w -> assertThat(w.wave()).isEqualTo(1));
    }

    @Test
    void aWaveQueuedAtTheEndIsOneWave() {
        doReturn(Optional.of(new TcModel.Build(CHAIN, "FAILURE", "finished", "pull/13335/head", null, null, null, null,
            null, null, null, null, new TcModel.Triggered("user", new TcModel.User(USER)), null, null, null, null, null)))
            .when(tc).findRunAllBuildForPr(anyString(), eq(PR));

        standing.sweep();

        assertThat(queued).containsExactly(CACHE1);
        assertThat(standing.waveStatus(PR, CHAIN)).hasValueSatisfying(w -> assertThat(w.wave()).isEqualTo(1));
    }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int code = 200;
        String body = "{}";

        if ("POST".equals(ex.getRequestMethod()) && path.equals("/app/rest/buildQueue")) {
            String sent = new String(ex.getRequestBody().readAllBytes(), UTF_8);
            String type = sent.replaceFirst("(?s).*\"buildType\":\\{\"id\":\"([^\"]+)\".*", "$1");
            queued.add(type);
            waiting.add("{\"id\":" + nextId.getAndIncrement() + ",\"state\":\"queued\",\"buildTypeId\":\"" + type
                + "\",\"branchName\":\"pull/" + PR + "/head\",\"triggered\":{\"type\":\"user\",\"user\":"
                + "{\"username\":\"" + USER + "\"}}}");
            code = sent.contains("\"queueAtTop\":true") ? 403 : 200;
            body = code == 200 ? waiting.get(waiting.size() - 1) : "Access denied";
        }
        else if (path.equals("/app/rest/builds") || path.equals("/app/rest/buildQueue"))
            body = "{\"build\":[" + String.join(",", waiting) + "]}";
        else if (path.equals("/app/rest/users/current"))
            body = "{\"username\":\"" + USER + "\"}";

        byte[] bytes = body.getBytes(UTF_8);
        ex.getResponseHeaders().add("Content-Type", code == 200 ? "application/json" : "text/plain");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
