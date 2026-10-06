package com.github.igniteprchecker.jira;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Early re-runs come from the rerun tracker watching a running chain, and it only watched chains the
 * checker started or someone had open on the PR page. RunAll 9389046 for PR 13335 was started from
 * the TeamCity UI with auto re-run on: three suites failed hours before the chain ended, and every
 * re-run came only after it finished. The standing sweep hands such chains to the tracker.
 *
 * <p>TeamCity is a real HTTP stub here, so the test covers the locator the sweep sends and the JSON
 * it reads back, not just the wiring.
 */
class RunningChainRegistrationTest {
    private static final String RUN_ALL = "IgniteTests24Java8_RunAll";
    private static final String RERUNNER = "avinogradov";

    private final GithubClient github = mock(GithubClient.class);
    private final RerunTracker tracker = mock(RerunTracker.class);
    private final List<String> locators = new CopyOnWriteArrayList<>();

    private HttpServer teamcity;
    private volatile int status = 200;
    private volatile String runningChains = "{\"build\":[]}";
    private StandingVisas standing;

    @BeforeEach
    void startTeamcity() throws IOException {
        teamcity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        teamcity.createContext("/app/rest/builds", ex -> {
            locators.add(param(ex.getRequestURI().getRawQuery(), "locator"));
            byte[] body = runningChains.getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        teamcity.start();

        ObjectMapper mapper = new ObjectMapper();
        TcClient tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + teamcity.getAddress().getPort() + "/"),
            new AnalysisProperties(null, RUN_ALL, null, null, null, null, null), new Metrics(mapper));
        standing = new StandingVisas(mapper, new SessionCodec(new SessionProperties(false, "test-secret"), mapper),
            tc, github, mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), tracker,
            mock(Warmer.class), mock(PendingCommits.class));
    }

    @AfterEach
    void stopTeamcity() {
        teamcity.stop(0);
    }

    @Test
    void aChainStartedFromTeamcityIsWatchedForEarlyReruns() {
        standing.enable(RERUNNER, "tc-token", null, null, false, true, false, false);
        runningChains = chains(chain(9389046, "pull/13335/head", RERUNNER));

        standing.sweep();

        verify(tracker).record(eq(13335), argThat(b -> b.id() == 9389046 && RUN_ALL.equals(b.buildTypeId())
            && "running".equals(b.state())));
    }

    @Test
    void onlyChainsOfUsersWithAutoRerunOnArePickedUp() {
        standing.enable(RERUNNER, "tc-token", null, null, false, true, false, false);
        standing.enable("visaOnly", "tc-token-2", null, null, false, false, false, false);
        runningChains = chains(
            chain(9389046, "pull/13335/head", RERUNNER),
            chain(9391271, "pull/13655/head", "visaOnly"),
            chain(9391123, "pull/13654/head", "stranger"),
            chain(9391000, "refs/heads/master", RERUNNER),
            "{\"id\":9390999,\"buildTypeId\":\"" + RUN_ALL + "\",\"state\":\"running\","
                + "\"branchName\":\"pull/13600/head\",\"triggered\":{\"type\":\"vcs\"}}");

        standing.sweep();

        verify(tracker).record(eq(13335), argThat(b -> b.id() == 9389046));
        verify(tracker, never()).record(eq(13655), any());
        verify(tracker, never()).record(eq(13654), any());
        verify(tracker, never()).record(eq(13600), any());
    }

    @Test
    void oneCallPerSweepListsRunningChainsOnEveryBranch() {
        standing.enable(RERUNNER, "tc-token", null, null, false, true, false, false);

        standing.sweep();

        assertThat(locators).hasSize(1);
        assertThat(locators.get(0))
            .contains("buildType:(id:" + RUN_ALL + ")")
            .contains("state:running")
            .contains("branch:(default:any)");
    }

    @Test
    void withoutAutoRerunTheSweepAsksTeamcityNothingNew() {
        standing.enable(RERUNNER, "tc-token", null, null, false, false, false, false);

        standing.sweep();

        assertThat(locators).isEmpty();
        verify(tracker, never()).record(anyInt(), any());
    }

    @Test
    void aTeamcityErrorDoesNotStopTheVisaSweep() {
        standing.enable(RERUNNER, "tc-token", null, null, false, true, false, false);
        status = 500;

        standing.sweep();

        verify(github).openPrs();
    }

    private static String chains(String... builds) {
        return "{\"build\":[" + String.join(",", builds) + "]}";
    }

    private static String chain(long id, String branch, String user) {
        return "{\"id\":" + id + ",\"buildTypeId\":\"" + RUN_ALL + "\",\"state\":\"running\","
            + "\"branchName\":\"" + branch + "\",\"webUrl\":\"https://ci2.ignite.apache.org/build/" + id + "\","
            + "\"buildType\":{\"id\":\"" + RUN_ALL + "\",\"name\":\"Run All\"},"
            + "\"triggered\":{\"type\":\"user\",\"user\":{\"username\":\"" + user + "\"}}}";
    }

    private static String param(String rawQuery, String name) {
        for (String kv : rawQuery.split("&")) {
            int at = kv.indexOf('=');
            if (kv.substring(0, at).equals(name))
                return URLDecoder.decode(kv.substring(at + 1), UTF_8);
        }

        return null;
    }
}
