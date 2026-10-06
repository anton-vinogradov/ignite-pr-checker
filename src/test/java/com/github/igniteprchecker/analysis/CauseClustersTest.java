package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.tc.TcClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * One test id can be a blocker in several suites, each failing its own way. The page puts each cluster's
 * members back together with its blockers, so a member names its suite as well as its test, and the
 * wire form is what is checked: a test id travels as a string, since a JS number would round it.
 */
class CauseClustersTest {
    private static final String TOK = "t";

    private static final long RECONNECT = 5272433775095107011L;

    private static final String LINUX = "IgniteTests24Java8_PlatformCPPCMakeLinux";

    private static final String WIN = "IgniteTests24Java8_PlatformCCMakeWinX64Release";

    private final TcClient tc = mock(TcClient.class);

    private final CauseClusters causes = new CauseClusters(tc, Executors.newSingleThreadExecutor());

    @Test
    void eachSuitesFailureOfOneTestJoinsTheClusterOfItsOwnMessage() {
        when(tc.testDetails(TOK, "o-linux")).thenReturn("java.lang.AssertionError: reconnect took 31 s");
        when(tc.testDetails(TOK, "o-win")).thenReturn("Connection refused: 127.0.0.1:10800");
        AnalysisResult res = new AnalysisResult(13335, 9389046L, "pull/13335/head", 0L,
            List.of(blocker(LINUX, "o-linux"), blocker(WIN, "o-win")), List.of(), List.of(), List.of(), List.of(),
            0, 0, false, 0, false, 0, 0, 0, 0, 0);

        JsonNode json = new ObjectMapper().valueToTree(causes.clusters(TOK, res));

        Map<String, List<String>> members = new LinkedHashMap<>();
        for (JsonNode c : json.get("clusters")) {
            List<String> tests = new ArrayList<>();
            c.path("tests").forEach(m -> tests.add(m.path("testId").asText() + " in " + m.path("suite").asText()));
            members.put(c.get("signature").asText(), tests);
        }
        assertThat(members).containsOnly(
            Map.entry("java.lang.AssertionError: reconnect took N s", List.of(RECONNECT + " in " + LINUX)),
            Map.entry("Connection refused: N.N.N.N:N", List.of(RECONNECT + " in " + WIN)));
    }

    private static TestVerdict blocker(String suite, String occurrenceId) {
        return new TestVerdict(RECONNECT, "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientReconnect", suite,
            0L, suite, occurrenceId, true, false, "blocker", "FFF", 3);
    }
}
