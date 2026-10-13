package com.github.igniteprchecker.suitefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The suite for a test class no suite runs, by a rule rather than a model. Calcite's IgniteFunctionParameterTest of
 * package exec.exp belongs in UtilTestSuite, next to IgniteSqlFunctionsTest of the same package, not in
 * ExecutionTestSuite with more classes of exec.rel. Indexing's IgniteCacheQueryNodeRestartTxSelfTest belongs next to
 * IgniteCacheQueryNodeRestartSelfTest2 in IgniteBinaryCacheQueryTestSuite2, though IgniteBinaryCacheQueryTestSuite3
 * lists more classes of its package. Core's GridJobMasterLeaveAwareSelfTest belongs next to GridJobContextSelfTest of
 * its very package, not GridJobMetricsProcessorLoadTest of a subpackage with a closer name; and GridThreadTest, whose
 * package no suite lists, next to classes of thread.pool, which the reason does not call its package.
 */
class SuiteChooserTest {
    private static final String CALCITE = "org.apache.ignite.internal.processors.query.calcite.";

    private static final String NEAR = "org.apache.ignite.internal.processors.cache.distributed.near.";

    private static final String INTERNAL = "org.apache.ignite.internal.";

    @Test
    void theSuiteOfTheSamePackageWinsOverOneWithMoreClassesNearby() {
        TestSuites.Suite util = suite("UtilTestSuite", CALCITE + "exec.exp.IgniteSqlFunctionsTest",
            CALCITE + "QueryCheckerTest");
        TestSuites.Suite execution = suite("ExecutionTestSuite", CALCITE + "exec.rel.ExecutionTest",
            CALCITE + "exec.rel.HashAggregateExecutionTest", CALCITE + "exec.rel.SortAggregateExecutionTest");

        Optional<SuiteChooser.Choice> choice = SuiteChooser.choose(CALCITE + "exec.exp.IgniteFunctionParameterTest",
            List.of(execution, util));

        assertThat(choice).hasValueSatisfying(c -> {
            assertThat(c.suite()).isSameAs(util);
            assertThat(c.neighbour()).isEqualTo("IgniteSqlFunctionsTest");
            assertThat(c.reason()).isEqualTo("it lists 1 class of the same package");
        });
    }

    @Test
    void theClosestNameWinsOverMoreClassesOfThePackage() {
        TestSuites.Suite suite2 = suite("IgniteBinaryCacheQueryTestSuite2",
            NEAR + "IgniteCacheQueryNodeRestartDistributedJoinSelfTest", NEAR + "IgniteCacheQueryNodeRestartSelfTest2");
        TestSuites.Suite suite3 = suite("IgniteBinaryCacheQueryTestSuite3",
            NEAR + "IgniteCachePartitionedFieldsQuerySelfTest", NEAR + "IgniteCachePartitionedQuerySelfTest",
            NEAR + "IgniteCacheQueryNoRebalanceSelfTest");

        Optional<SuiteChooser.Choice> choice = SuiteChooser.choose(NEAR + "IgniteCacheQueryNodeRestartTxSelfTest",
            List.of(suite3, suite2));

        assertThat(choice).hasValueSatisfying(c -> {
            assertThat(c.suite()).isSameAs(suite2);
            assertThat(c.neighbour()).as("the name just before it, as Ignite's suite has it")
                .isEqualTo("IgniteCacheQueryNodeRestartSelfTest2");
            assertThat(c.reason()).isEqualTo("it lists 2 classes of the same package, and"
                + " `IgniteCacheQueryNodeRestartSelfTest2` has the closest name");
        });
    }

    @Test
    void asNearAClassInTwoSuitesGoesToTheOneWithMoreOfItsPackage() {
        TestSuites.Suite few = suite("IgniteCacheTestSuite", NEAR + "GridCacheNearOneNodeSelfTest");
        TestSuites.Suite many = suite("IgniteCacheTestSuite2", NEAR + "GridCacheNearMultiNodeSelfTest",
            NEAR + "GridCacheNearReadersSelfTest");

        assertThat(SuiteChooser.choose(NEAR + "GridCacheNearEvictionSelfTest", List.of(few, many)))
            .hasValueSatisfying(c -> assertThat(c.suite()).isSameAs(many));
    }

    @Test
    void whenAllElseTiesTheFirstSuiteByPathWins() {
        TestSuites.Suite b = suite("IgniteCacheTestSuite2", NEAR + "GridCacheNearReadersSelfTest");
        TestSuites.Suite a = suite("IgniteCacheTestSuite", NEAR + "GridCacheNearReadersSelfTest");

        assertThat(SuiteChooser.choose(NEAR + "GridCacheNearEvictionSelfTest", List.of(b, a)))
            .hasValueSatisfying(c -> assertThat(c.suite()).isSameAs(a));
    }

    @Test
    void withNoSuiteOfItsPackageTheNearestPackageWinsAndIsNamed() {
        TestSuites.Suite dht = suite("IgniteCacheTestSuite3",
            "org.apache.ignite.internal.processors.cache.distributed.dht.GridCacheDhtPreloadSelfTest");
        TestSuites.Suite kernal = suite("IgniteKernalSelfTestSuite", "org.apache.ignite.internal.GridVersionSelfTest",
            "org.apache.ignite.internal.GridNodeMetricsLogSelfTest");

        assertThat(SuiteChooser.choose(NEAR + "GridCacheNearTxForceKeyTest", List.of(kernal, dht)))
            .hasValueSatisfying(c -> {
                assertThat(c.suite()).isSameAs(dht);
                assertThat(c.reason()).isEqualTo("no suite lists a class of the same package, and it lists 1 class"
                    + " under `org.apache.ignite.internal.processors.cache.distributed`, the nearest");
            });
    }

    @Test
    void aClassOfTheSamePackageIsNearerThanOneOfASubpackageWithACloserName() {
        TestSuites.Suite jobMetrics = suite("IgniteJobMetricsSelfTestSuite",
            INTERNAL + "processors.jobmetrics.GridJobMetricsProcessorLoadTest");
        TestSuites.Suite computeGrid = suite("IgniteComputeGridTestSuite", INTERNAL + "GridJobContextSelfTest",
            INTERNAL + "managers.checkpoint.GridCheckpointTaskSelfTest",
            INTERNAL + "processors.compute.ComputeJobChangePriorityTest");

        Optional<SuiteChooser.Choice> choice = SuiteChooser.choose(INTERNAL + "GridJobMasterLeaveAwareSelfTest",
            List.of(jobMetrics, computeGrid));

        assertThat(choice).hasValueSatisfying(c -> {
            assertThat(c.suite()).isSameAs(computeGrid);
            assertThat(c.neighbour()).isEqualTo("GridJobContextSelfTest");
            assertThat(c.reason()).as("the classes of subpackages are not counted")
                .isEqualTo("it lists 1 class of the same package");
        });
    }

    @Test
    void classesOfASubpackageAreNotCalledClassesOfTheSamePackage() {
        TestSuites.Suite util = suite("IgniteUtilSelfTestSuite",
            INTERNAL + "thread.pool.GridThreadPoolExecutorServiceSelfTest",
            INTERNAL + "thread.pool.IgniteThreadPoolSizeTest", INTERNAL + "thread.pool.ThreadPoolMetricsTest");

        assertThat(SuiteChooser.choose(INTERNAL + "thread.GridThreadTest", List.of(util)))
            .hasValueSatisfying(c -> {
                assertThat(c.neighbour()).isEqualTo("GridThreadPoolExecutorServiceSelfTest");
                assertThat(c.reason()).isEqualTo("no suite lists a class of the same package, and it lists 3 classes"
                    + " under `org.apache.ignite.internal.thread`, the nearest");
            });
    }

    @Test
    void theNeighbourIsTheNameJustBeforeItOrElseTheFirstAfter() {
        TestSuites.Suite s = suite("IgniteCacheTestSuite", NEAR + "CacheAlphaTest", NEAR + "CacheGammaTest",
            NEAR + "CacheDeltaTest");

        assertThat(SuiteChooser.choose(NEAR + "CacheEpsilonTest", List.of(s)))
            .hasValueSatisfying(c -> assertThat(c.neighbour()).isEqualTo("CacheDeltaTest"));
        assertThat(SuiteChooser.choose(NEAR + "Cache0Test", List.of(s)))
            .hasValueSatisfying(c -> assertThat(c.neighbour()).isEqualTo("CacheAlphaTest"));
    }

    /** A class named through a wildcard import has no known package, so it can't be near anything. */
    @Test
    void aClassOfAnUnknownPackageIsNotANeighbour() {
        TestSuites.Suite s = new TestSuites.Suite("modules/core/src/test/java/IgniteCacheTestSuite.java", "",
            List.of(new TestSuites.Member("GridCacheNearReadersSelfTest", null),
                new TestSuites.Member("GridVersionSelfTest", "org.apache.ignite.internal.GridVersionSelfTest")));

        assertThat(SuiteChooser.choose(NEAR + "GridCacheNearEvictionSelfTest", List.of(s)))
            .hasValueSatisfying(c -> assertThat(c.neighbour()).isEqualTo("GridVersionSelfTest"));
    }

    @Test
    void noSuiteListsAClassOfAPackageNearItsOwn() {
        TestSuites.Suite s = suite("IgniteCacheTestSuite", NEAR + "GridCacheNearReadersSelfTest");

        assertThat(SuiteChooser.choose("com.example.StrayTest", List.of(s))).isEmpty();
        assertThat(SuiteChooser.choose("DefaultPackageTest", List.of(s))).isEmpty();
        assertThat(SuiteChooser.choose(NEAR + "GridCacheNearEvictionSelfTest", List.of())).isEmpty();
    }

    private static TestSuites.Suite suite(String name, String... classes) {
        return new TestSuites.Suite("modules/core/src/test/java/org/apache/ignite/testsuites/" + name + ".java",
            "org.apache.ignite.testsuites", Arrays.stream(classes)
                .map(c -> new TestSuites.Member(c.substring(c.lastIndexOf('.') + 1), c)).toList());
    }
}
