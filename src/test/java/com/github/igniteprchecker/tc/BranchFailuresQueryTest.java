package com.github.igniteprchecker.tc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The branch's failed builds since a chain, against a TeamCity that answers the way ci2 did for RunAll
 * 9389046 of apache/ignite#13335 (queued 20:25:56, started 21:22:42). Asked with
 * {@code sinceBuild:(id:9389046)}, ci2 left out everything that started before 22:05:35, 43 minutes
 * into the chain; asked with {@code sinceDate} at the chain's queue time, it returned all of it.
 */
class BranchFailuresQueryTest {
    private static final String TOK = "t";

    private static final int PR = 13335;

    private static final long CHAIN = 9389046L;

    private static final String CHAIN_QUEUED = "20261005T202556+0000";

    /** Where ci2 cut {@code sinceBuild:(id:9389046)}: the start of the earliest build it returned. */
    private static final String SINCE_BUILD_CUT = "20261005T220535+0000";

    /**
     * ci2's FAILURE builds on the branch after the chain was queued, plus an early re-run of
     * ZooKeeper 4: such a re-run is queued as soon as the suite fails, and ZK4 failed at 21:46.
     */
    private static final List<Failed> CI2 = List.of(
        new Failed(9389219L, "IgniteTests24Java8_Queries5", "20261005T234000+0000"),
        new Failed(9388987L, "IgniteTests24Java8_Spring", "20261005T223138+0000"),
        new Failed(9389035L, "IgniteTests24Java8_Cache17", "20261005T220828+0000"),
        new Failed(9389033L, "IgniteTests24Java8_CacheFailover5", "20261005T220757+0000"),
        new Failed(9389029L, "IgniteTests24Java8_Queries6", "20261005T220613+0000"),
        new Failed(9389028L, "IgniteTests24Java8_Queries5", "20261005T220535+0000"),
        new Failed(9389045L, "IgniteTests24Java8_PlatformNetWindows3", "20261005T220118+0000"),
        new Failed(9389005L, "IgniteTests24Java8_Cache14", "20261005T215832+0000"),
        new Failed(9388909L, "IgniteTests24Java8_Cache12", "20261005T215231+0000"),
        new Failed(9389101L, "IgniteTests24Java8_ZooKeeperDiscovery4", "20261005T214710+0000"),
        new Failed(9388999L, "IgniteTests24Java8_DiskPageCompressions1", "20261005T214622+0000"),
        new Failed(9388972L, "IgniteTests24Java8_PlatformCPPCMakeLinuxClang", "20261005T214510+0000"),
        new Failed(9388997L, "IgniteTests24Java8_ZooKeeperDiscovery4", "20261005T213748+0000"),
        new Failed(9388973L, "IgniteTests24Java8_PlatformNetCoreLinux", "20261005T213427+0000"),
        new Failed(CHAIN, "IgniteTests24Java8_RunAll", "20261005T212242+0000"));

    private HttpServer server;

    private TcClient tc;

    @BeforeEach
    void startTeamCity() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/rest/builds", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String locator = URLDecoder.decode(query.replaceAll(".*locator=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            byte[] body = answer(locator).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        tc = new TcClient(new TeamcityProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            new AnalysisProperties(null, null, null, null, null, null, null), mock(Metrics.class));
    }

    @AfterEach
    void stopTeamCity() {
        server.stop(0);
    }

    @Test
    void aRerunThatStartedEarlyInTheChainIsNotCutOff() {
        List<TcModel.Build> failed = tc.failedBuildsSince(TOK, PR, CHAIN_QUEUED);

        assertThat(failed).extracting(TcModel.Build::id)
            .as("the early re-run of ZK4, and the suites that failed in the chain's first 43 minutes")
            .contains(9389101L, 9388997L, 9388999L, 9388972L, 9388973L);
    }

    /** TeamCity's answer: {@code sinceDate} by start time, {@code sinceBuild} where ci2 really cut it. */
    private static String answer(String locator) {
        String since = sinceOf(locator);

        return CI2.stream()
            .filter(b -> locator.contains("branch:(name:pull/" + PR + "/head)") && locator.contains("status:FAILURE"))
            .filter(b -> since == null || TcDates.epochSeconds(b.startDate()) >= TcDates.epochSeconds(since))
            .map(b -> "{\"id\":" + b.id() + ",\"buildTypeId\":\"" + b.buildTypeId()
                + "\",\"status\":\"FAILURE\",\"state\":\"finished\",\"startDate\":\"" + b.startDate() + "\"}")
            .collect(Collectors.joining(",", "{\"build\":[", "]}"));
    }

    private static String sinceOf(String locator) {
        Matcher date = Pattern.compile("sinceDate:([0-9T+-]+)").matcher(locator);
        if (date.find())
            return date.group(1);

        return locator.contains("sinceBuild:(id:" + CHAIN + ")") ? SINCE_BUILD_CUT : null;
    }

    private record Failed(long id, String buildTypeId, String startDate) {
    }
}
