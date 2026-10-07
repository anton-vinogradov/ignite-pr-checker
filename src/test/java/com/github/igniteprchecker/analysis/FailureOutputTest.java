package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Failure outputs as ci2 stored them in October 2026. Every Ignite node start logs
 * "SYSTEM_CRITICAL_OPERATION_TIMEOUT" to stdout, and the page searched the whole output for "timeout": about
 * 14 of 51 blockers, testGroupReservation's "expected:&lt;false&gt; but was:&lt;true&gt;" among them, were
 * called "environment/timing — a re-run may pass". A hung test's output with its thread dump was 707 KB,
 * and all of it went into the "ai" prompt.
 */
class FailureOutputTest {
    private static final Path SAMPLES = Path.of("src/test/resources/details");

    @Test
    void assertionWhoseStdoutMentionsTimeoutsIsAnAssertion() throws IOException {
        String details = sample("assertion-testGroupReservation.txt");

        assertThat(details).contains("SYSTEM_CRITICAL_OPERATION_TIMEOUT");
        assertThat(FailureOutput.kind(details)).isEqualTo(FailureOutput.Kind.ASSERTION);
        assertThat(FailureOutput.head(details))
            .startsWith("expected:<false> but was:<true>")
            .contains("EvictionWhilePartitionGroupIsReservedTest.testGroupReservation(")
            .doesNotContain("Starting test class");
    }

    @Test
    void failedCheckIsAnAssertionWhateverItMentions() {
        assertThat(FailureOutput.kind("java.lang.AssertionError: Timeout while waiting for rebalance\n"
            + "\tat org.apache.ignite.internal.RebalanceTest.testRebalance(RebalanceTest.java:57)"))
            .isEqualTo(FailureOutput.Kind.ASSERTION);
        assertThat(FailureOutput.kind("class org.apache.ignite.IgniteCheckedException: Failed to wait for topology"))
            .isEqualTo(FailureOutput.Kind.ENVIRONMENT);
    }

    @Test
    void dotNetStackEndsAtTheFirstSeparator() throws IOException {
        String details = sample("dotnet-TestClusterTopologyChanges.txt");

        assertThat(details).contains("SYSTEM_CRITICAL_OPERATION_TIMEOUT");
        assertThat(FailureOutput.kind(details)).isNull();
        assertThat(FailureOutput.head(details))
            .startsWith("System.InvalidOperationException : Sequence contains more than one element")
            .contains("ServicesAwarenessTests.TestClusterTopologyChanges(String serviceName)")
            .doesNotContain("Socket connection");
    }

    @Test
    void failedBoostCheckIsAnAssertion() throws IOException {
        assertThat(FailureOutput.kind(sample("cpp-IgniteClientReconnect-agent1.txt")))
            .isEqualTo(FailureOutput.Kind.ASSERTION);
    }

    @Test
    void hangKeepsItsStackAndTheTestThreadWithinTheLimit() throws IOException {
        String details = sample("hang-testPutAllAsyncFailoverManyThreads.txt.gz");

        String kept = FailureOutput.trim(details);

        assertThat(FailureOutput.kind(details)).isEqualTo(FailureOutput.Kind.HANG);
        assertThat(details.length()).isGreaterThan(700_000);
        assertThat(kept.length()).isLessThan(FailureOutput.MAX_CHARS + 200);
        assertThat(kept)
            .startsWith("Test has been timed out [test=testPutAllAsyncFailoverManyThreads, timeout=120000]")
            .contains("at org.apache.ignite.testframework.junits.GridAbstractTest.runTest(GridAbstractTest.java:2495)")
            .contains("[the test's thread in the thread dump:]\nThread [name=\"test-runner-#62790")
            .contains("CacheAsyncOperationsFailoverAbstractTest.putAllAsyncFailover(")
            .endsWith(String.format(Locale.ROOT, "[truncated: %,d of 707,864 characters kept; the whole output is in "
                + "TeamCity]", kept.indexOf("\n[truncated:")));
        assertThat(FailureOutput.kind(kept)).isEqualTo(FailureOutput.Kind.HANG);
    }

    @Test
    void hangWhoseStackNearlyFillsTheLimitIsCutAllTheSame() {
        String message = "Test has been timed out [test=testPutAllAsyncFailover, timeout=120000]";
        StringBuilder head = new StringBuilder(message).append("\n------- Stdout: -------\n")
            .append("class org.apache.ignite.IgniteException: ").append(message).append('\n');
        while (head.length() < FailureOutput.MAX_CHARS - 200)
            head.append("\tat org.apache.ignite.internal.util.IgniteUtils.sleep(IgniteUtils.java:8172)\n");
        head.append("\tat ").append("x".repeat(FailureOutput.MAX_CHARS - 20 - head.length() - 5)).append('\n');
        String details = head + "------- Stdout: -------\n"
            + "Thread [name=\"test-runner-#62790%near.CachePutAllFailoverTest%\", id=1, state=WAITING]\n"
            + "    at o.a.i.i.util.GridFutureAdapter.get(GridFutureAdapter.java:178)\n" + "x".repeat(10_000) + '\n';

        String kept = FailureOutput.trim(details);

        assertThat(FailureOutput.head(details)).hasSize(head.length());
        assertThat(kept.substring(0, kept.indexOf("\n[truncated:"))).hasSizeLessThanOrEqualTo(FailureOutput.MAX_CHARS)
            .startsWith(head.toString());
    }

    @Test
    void hamcrestMessageOnLinesOfItsOwnKeepsItsStack() {
        String details = """

            Expected: is <true>
                 but: was <false>
            ------- Stdout: -------
            java.lang.AssertionError:\s
            Expected: is <true>
                 but: was <false>
            \tat org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:20)
            \tat org.apache.ignite.internal.processors.cache.CacheGroupMetricsTest.testAllocatedPages(CacheGroupMetricsTest.java:316)
            ------- Stdout: -------
            [01:16:38] ignoredFailureTypes=[SYSTEM_CRITICAL_OPERATION_TIMEOUT]
            """;

        assertThat(FailureOutput.head(details))
            .contains("CacheGroupMetricsTest.testAllocatedPages(")
            .doesNotContain("ignoredFailureTypes");
    }

    @Test
    void outputWithinTheLimitStaysWhole() throws IOException {
        String details = sample("dotnet-TestClusterTopologyChanges.txt");

        assertThat(FailureOutput.trim(details)).isSameAs(details);
        assertThat(FailureOutput.trim(null)).isNull();
    }

    static String sample(String name) throws IOException {
        try (InputStream in = Files.newInputStream(SAMPLES.resolve(name))) {
            InputStream text = name.endsWith(".gz") ? new GZIPInputStream(in) : in;

            return new String(text.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
