package com.github.igniteprchecker.analysis;

import static com.github.igniteprchecker.analysis.FailureOutputTest.sample;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Root causes once split one failure in two and joined unrelated ones: "java.lang.AssertionError: X" and
 * "X" were two causes, each hung test was its own cause because its name is in the message, the same C++
 * check failed in two causes because the agents' work directories differ, and every bare
 * "expected:&lt;false&gt; but was:&lt;true&gt;" was one cause whatever check failed, as was every failed hamcrest
 * assertThat, whose message starts on a line of its own.
 */
class CauseSignatureTest {
    @Test
    void exceptionClassInFrontIsNotPartOfTheCause() {
        assertThat(CauseClusters.signature("java.lang.AssertionError: Partition 7 is not evicted"))
            .isEqualTo(CauseClusters.signature("Partition 7 is not evicted"))
            .isEqualTo("Partition N is not evicted");
        assertThat(CauseClusters.signature("class org.apache.ignite.IgniteException: Failed to start IgniteSpringBean"))
            .isEqualTo("Failed to start IgniteSpringBean");
    }

    @Test
    void hungTestsShareOneCauseWithoutTheirNames() throws IOException {
        String hang = CauseClusters.signature(sample("hang-testPutAllAsyncFailoverManyThreads.txt.gz"));

        assertThat(hang).isEqualTo("Test has been timed out");
        assertThat(CauseClusters.signature("Test has been timed out [test=testRestoreSnapshot, timeout=600000]"))
            .isEqualTo(hang);
    }

    @Test
    void sameCheckOnTwoAgentsIsOneCause() throws IOException {
        String first = CauseClusters.signature(sample("cpp-IgniteClientReconnect-agent1.txt"));

        assertThat(first)
            .isEqualTo(CauseClusters.signature(sample("cpp-IgniteClientReconnect-agent2.txt")))
            .isEqualTo("modules/platforms/cpp/thin-client-test/src/ignite_client_test.cpp(N): check WaitForConnections(N) "
                + "has failed");
    }

    @Test
    void bareComparisonNamesTheFailingLineOfTheTest() throws IOException {
        String reservation = CauseClusters.signature(sample("assertion-testGroupReservation.txt"));

        assertThat(reservation)
            .isEqualTo("expected:<false> but was:<true> at "
                + "EvictionWhilePartitionGroupIsReservedTest.testGroupReservation");
        assertThat(CauseClusters.signature("""
            expected:<false> but was:<true>
            ------- Stdout: -------
            java.lang.AssertionError: expected:<false> but was:<true>
            \tat org.junit.Assert.fail(Assert.java:89)
            \tat org.apache.ignite.testframework.junits.JUnitAssertAware.assertEquals(JUnitAssertAware.java:70)
            \tat org.apache.ignite.internal.processors.cache.GridCacheRebalancingTest.testRebalance(GridCacheRebalancingTest.java:91)
            ------- Stdout: -------
            [01:16:38] >>> Starting test class: GridCacheRebalancingTest <<<
            """)).isEqualTo("expected:<false> but was:<true> at GridCacheRebalancingTest.testRebalance");
        assertThat(CauseClusters.signature("java.lang.AssertionError\n\tat org.junit.Assert.fail(Assert.java:87)\n"
            + "\tat org.apache.ignite.internal.TxRecoveryTest.testCommit(TxRecoveryTest.java:40)\n"))
            .isEqualTo("AssertionError at TxRecoveryTest.testCommit");
    }

    @Test
    void hamcrestChecksOfDifferentTestsAreDifferentCauses() {
        String metrics = hamcrest("org.apache.ignite.internal.processors.cache.CacheGroupMetricsTest.testAllocatedPages"
            + "(CacheGroupMetricsTest.java:316)");
        String snapshot = hamcrest("org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotTest"
            + ".testRestore(SnapshotTest.java:88)");

        assertThat(CauseClusters.signature(metrics))
            .isEqualTo("Expected: is <true> at CacheGroupMetricsTest.testAllocatedPages");
        assertThat(CauseClusters.signature(snapshot)).isEqualTo("Expected: is <true> at SnapshotTest.testRestore");
    }

    @Test
    void dotNetMessageLosesItsExceptionClass() throws IOException {
        assertThat(CauseClusters.signature(sample("dotnet-TestClusterTopologyChanges.txt")))
            .isEqualTo("Sequence contains more than one element");
    }

    /** A failed hamcrest assertThat as TeamCity keeps it: the message starts on a new line, under the class. */
    private static String hamcrest(String testFrame) {
        return "\nExpected: is <true>\n     but: was <false>\n"
            + "------- Stdout: -------\n"
            + "java.lang.AssertionError: \nExpected: is <true>\n     but: was <false>\n"
            + "\tat org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:20)\n"
            + "\tat org.hamcrest.MatcherAssert.assertThat(MatcherAssert.java:6)\n"
            + "\tat " + testFrame + "\n"
            + "------- Stdout: -------\n"
            + "[01:16:38] ignoredFailureTypes=[SYSTEM_CRITICAL_OPERATION_TIMEOUT]\n";
    }
}
