package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The blockers of two runs are compared per test and suite: one test id runs in several suites of a chain
 * (the C++ thin-client tests run on Windows, Linux and Clang), and each suite's failure is a blocker of
 * its own, so one suite getting fixed is a fixed blocker even while another suite still fails the test.
 */
class RunDeltaStoreTest {
    private static final int PR = 13335;

    private static final long RECONNECT = 5272433775095107011L;

    private static final String NAME = "IgniteThinClientTest: IgniteClientTestSuite: IgniteClientReconnect";

    private static final String LINUX = "IgniteTests24Java8_PlatformCPPCMakeLinux";

    private static final String CLANG = "IgniteTests24Java8_PlatformCPPCMakeLinuxClang";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aTestFixedInOneSuiteIsFixedThereWhileItPersistsInTheOther() {
        RunDeltaStore deltas = new RunDeltaStore(mapper);
        deltas.onResult(PR, 9389046L, List.of(blocker(LINUX), blocker(CLANG)), 0);
        deltas.onResult(PR, 9390500L, List.of(blocker(LINUX)), 0);

        RunDeltaStore.Delta d = deltas.delta(PR);

        assertThat(d.fixed()).containsExactly(new RunDeltaStore.ChangedTest(NAME, CLANG, CLANG));
        assertThat(d.appeared()).isEmpty();
        assertThat(d.persisting()).isEqualTo(1);
    }

    @Test
    void theComparisonSurvivesARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("delta.json");
        RunDeltaStore first = new RunDeltaStore(mapper);
        first.onResult(PR, 9389046L, List.of(blocker(LINUX)), 0);
        first.onResult(PR, 9390500L, List.of(blocker(LINUX), blocker(CLANG)), 0);
        first.saveTo(file);

        RunDeltaStore reloaded = new RunDeltaStore(mapper);
        reloaded.loadFrom(file);

        assertThat(reloaded.delta(PR)).isEqualTo(first.delta(PR));
        assertThat(reloaded.delta(PR).appeared()).containsExactly(new RunDeltaStore.ChangedTest(NAME, CLANG, CLANG));
    }

    /**
     * Blocker sets written by the previous rules were keyed by test id alone, one suite standing for all:
     * the file still loads, and those sets are not compared against.
     */
    @Test
    void blockerSetsKeyedByTestIdAloneLoadAndAreLeftOut(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("delta.json");
        Files.writeString(file, "[{\"pr\":" + PR + ",\"rules\":5,"
            + "\"latest\":{\"buildId\":9390500,\"blockers\":{\"" + RECONNECT + "\":"
            + "{\"name\":\"" + NAME + "\",\"suite\":\"" + LINUX + "\",\"suiteName\":\"" + LINUX + "\"}}},"
            + "\"prev\":{\"buildId\":9389046,\"blockers\":{}},"
            + "\"history\":[{\"buildId\":9390500,\"at\":1,\"blockers\":1,\"brokenSuites\":0}]}]");

        RunDeltaStore reloaded = new RunDeltaStore(mapper);
        reloaded.loadFrom(file);

        assertThat(reloaded.delta(PR)).isNull();
        assertThat(reloaded.history(PR)).isEmpty();
    }

    private static TestVerdict blocker(String suite) {
        return new TestVerdict(RECONNECT, NAME, suite, 0L, suite, null, true, false, "blocker", "FFF", 3);
    }
}
