package com.github.igniteprchecker.suitefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Calcite's IgniteFunctionParameterTest, of package exec.exp, belongs in UtilTestSuite next to IgniteSqlFunctionsTest,
 * not in ExecutionTestSuite; a suite of the core module lists its classes with GridTestUtils.addTestIfNeeded, another
 * with suite.add, and some list the classes to leave out. A class goes in as a copy of its neighbour's line, so each
 * suite keeps its own form, and a line that is not one class of a list a suite runs is never copied.
 */
class TestSuitesTest {
    private static final String CALCITE = "modules/calcite/src/test/java/";

    private static final String UTIL_SUITE = """
        /*
         * Licensed to the Apache Software Foundation (ASF) under one or more
         */

        package org.apache.ignite.testsuites;

        import org.apache.ignite.internal.processors.query.calcite.QueryCheckerTest;
        import org.apache.ignite.internal.processors.query.calcite.exec.ClosableIteratorsHolderTest;
        import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest;
        import org.junit.runner.RunWith;
        import org.junit.runners.Suite;

        /**
         * Calcite utility classes tests.
         */
        @RunWith(Suite.class)
        @Suite.SuiteClasses({
            ClosableIteratorsHolderTest.class,
            QueryCheckerTest.class,
            IgniteSqlFunctionsTest.class,
        })
        public class UtilTestSuite {
        }
        """;

    private static final String DYNAMIC_SUITE = """
        package org.apache.ignite.testsuites;

        import java.util.ArrayList;
        import java.util.Collection;
        import java.util.List;
        import org.apache.ignite.cache.IgniteWarmupClosureSelfTest;
        import org.apache.ignite.testframework.GridTestUtils;

        public class IgniteCacheTestSuite {
            public static List<Class<?>> suite(Collection<Class> ignoredTests) {
                List<Class<?>> suite = new ArrayList<>();

                GridTestUtils.addTestIfNeeded(suite, IgniteWarmupClosureSelfTest.class, ignoredTests);
                GridTestUtils.addTestIfNeeded(suite, CacheLocalSelfTest.class, ignoredTests); // IGNITE-1234

                return suite;
            }
        }
        """;

    @Test
    void anAnnotatedSuiteGetsALineLikeItsNeighbourAndAnImportInOrder() {
        TestSuites.Edit edit = TestSuites.add(UTIL_SUITE, "IgniteSqlFunctionsTest",
            "org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest");

        assertThat(edit.failure()).isNull();
        assertThat(edit.source()).isEqualTo(UTIL_SUITE
            .replace("import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest;\n",
                "import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest;\n"
                    + "import org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest;\n")
            .replace("    IgniteSqlFunctionsTest.class,\n",
                "    IgniteSqlFunctionsTest.class,\n    IgniteFunctionParameterTest.class,\n"));
    }

    @Test
    void aLastEntryWithoutACommaTakesTheNewOneAboveIt() {
        String suite = UTIL_SUITE.replace("    IgniteSqlFunctionsTest.class,\n", "    IgniteSqlFunctionsTest.class\n");

        TestSuites.Edit edit = TestSuites.add(suite, "IgniteSqlFunctionsTest",
            "org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest");

        assertThat(edit.source()).contains("    QueryCheckerTest.class,\n    IgniteFunctionParameterTest.class,\n"
            + "    IgniteSqlFunctionsTest.class\n})");
    }

    @Test
    void aDynamicSuiteGetsTheSameCallWithoutTheNeighboursComment() {
        TestSuites.Edit edit = TestSuites.add(DYNAMIC_SUITE, "CacheLocalSelfTest",
            "org.apache.ignite.testsuites.CacheLocalReadTest");

        assertThat(edit.source()).isEqualTo(DYNAMIC_SUITE.replace("// IGNITE-1234\n",
            "// IGNITE-1234\n        GridTestUtils.addTestIfNeeded(suite, CacheLocalReadTest.class, ignoredTests);\n"));
    }

    @Test
    void aSuiteThatAddsToItsListGetsOneMoreAdd() {
        String suite = """
            package org.apache.ignite.testsuites;

            import java.util.ArrayList;
            import java.util.List;
            import org.apache.ignite.internal.encryption.CacheGroupKeyChangeTest;

            public class IgniteEncryptionTestSuite {
                public static List<Class<?>> suite() {
                    List<Class<?>> suite = new ArrayList<>();
                    suite.add(CacheGroupKeyChangeTest.class);

                    return suite;
                }
            }
            """;

        TestSuites.Edit edit = TestSuites.add(suite, "CacheGroupKeyChangeTest",
            "org.apache.ignite.internal.encryption.MasterKeyChangeTest");

        assertThat(edit.source())
            .contains("import org.apache.ignite.internal.encryption.CacheGroupKeyChangeTest;\n"
                + "import org.apache.ignite.internal.encryption.MasterKeyChangeTest;\n")
            .contains("        suite.add(CacheGroupKeyChangeTest.class);\n        suite.add(MasterKeyChangeTest.class);\n");
    }

    @Test
    void theSuiteListsWhatItRunsByPackage() {
        TestSuites.Suite suite = TestSuites.read(CALCITE + "org/apache/ignite/testsuites/UtilTestSuite.java",
            UTIL_SUITE).orElseThrow();

        assertThat(suite.name()).isEqualTo("UtilTestSuite");
        assertThat(suite.members()).containsExactly(
            new TestSuites.Member("ClosableIteratorsHolderTest",
                "org.apache.ignite.internal.processors.query.calcite.exec.ClosableIteratorsHolderTest"),
            new TestSuites.Member("QueryCheckerTest",
                "org.apache.ignite.internal.processors.query.calcite.QueryCheckerTest"),
            new TestSuites.Member("IgniteSqlFunctionsTest",
                "org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteSqlFunctionsTest"));
        assertThat(TestSuites.read(CALCITE + "org/apache/ignite/testsuites/IgniteCacheTestSuite.java", DYNAMIC_SUITE)
            .orElseThrow().members()).extracting(TestSuites.Member::fqcn).containsExactly(
                "org.apache.ignite.cache.IgniteWarmupClosureSelfTest", "org.apache.ignite.testsuites.CacheLocalSelfTest");
    }

    /** The classes a suite leaves out, a commented one, and one sharing a line are not its entries. */
    @Test
    void onlyALineOfOneClassInAListThatRunsIsAnEntry() {
        String suite = """
            package org.apache.ignite.testsuites;

            import java.util.Arrays;
            import java.util.HashSet;
            import java.util.Set;
            import org.junit.runner.RunWith;
            import org.junit.runners.Suite;

            @RunWith(Suite.class)
            @Suite.SuiteClasses({ATest.class, BTest.class})
            public class MixedTestSuite {
                public static Set<Class> suite() {
                    Set<Class> ignoredTests = new HashSet<>();
                    ignoredTests.add(IgnoredTest.class);

                    Set<Class> skipped = new HashSet<>(Arrays.asList(
                        SkippedTest.class,
                        OtherSkippedTest.class));

                    // suite.add(CommentedTest.class);
                    /*
                    suite.add(BlockCommentedTest.class);
                    */

                    return ignoredTests;
                }
            }
            """;

        assertThat(TestSuites.read("MixedTestSuite.java", suite)).isEmpty();
        assertThat(TestSuites.add(suite, "IgnoredTest", "org.apache.ignite.testsuites.NewTest").failure())
            .isEqualTo("it does not list `IgnoredTest` on a line of its own");
        assertThat(TestSuites.add(suite, "ATest", "org.apache.ignite.testsuites.NewTest").failure())
            .isEqualTo("it does not list `ATest` on a line of its own");
    }

    @Test
    void aClassAlreadyListedOrAnUnknownNeighbourIsRefused() {
        assertThat(TestSuites.add(UTIL_SUITE, "QueryCheckerTest", "org.apache.other.IgniteSqlFunctionsTest").failure())
            .isEqualTo("it already lists `IgniteSqlFunctionsTest`");
        assertThat(TestSuites.add(UTIL_SUITE, "NoSuchTest", "org.apache.NewTest").failure())
            .isEqualTo("it does not list `NoSuchTest` on a line of its own");
        assertThat(TestSuites.add(DYNAMIC_SUITE + DYNAMIC_SUITE, "IgniteWarmupClosureSelfTest", "org.apache.NewTest")
            .failure()).isEqualTo("it lists `IgniteWarmupClosureSelfTest` on 2 lines");
    }

    /** A neighbour written with its package, or a name another import holds, gets the full name and no import. */
    @Test
    void aQualifiedNeighbourOrAClashingNameIsWrittenInFull() {
        String qualified = UTIL_SUITE.replace("    QueryCheckerTest.class,\n",
            "    org.apache.ignite.internal.processors.query.calcite.QueryCheckerTest.class,\n");
        TestSuites.Edit full = TestSuites.add(qualified,
            "QueryCheckerTest", "org.apache.ignite.internal.processors.query.calcite.planner.PlannerTimeoutTest");

        assertThat(full.source()).contains("    org.apache.ignite.internal.processors.query.calcite.QueryCheckerTest"
            + ".class,\n    org.apache.ignite.internal.processors.query.calcite.planner.PlannerTimeoutTest.class,\n")
            .doesNotContain("import org.apache.ignite.internal.processors.query.calcite.planner");

        String helper = UTIL_SUITE.replace("import org.junit.runner.RunWith;\n",
            "import org.apache.ignite.internal.util.HelperTest;\nimport org.junit.runner.RunWith;\n");
        TestSuites.Edit clash = TestSuites.add(helper, "QueryCheckerTest", "org.apache.ignite.other.HelperTest");

        assertThat(clash.source()).isEqualTo(helper.replace("    QueryCheckerTest.class,\n",
            "    QueryCheckerTest.class,\n    org.apache.ignite.other.HelperTest.class,\n"));
    }

    @Test
    void aClassInTheSuitesPackageNeedsNoImport() {
        TestSuites.Edit edit = TestSuites.add(UTIL_SUITE, "QueryCheckerTest", "org.apache.ignite.testsuites.LocalTest");

        assertThat(edit.source()).contains("    QueryCheckerTest.class,\n    LocalTest.class,\n")
            .doesNotContain("import org.apache.ignite.testsuites.LocalTest;");
    }

    @Test
    void pathsTellTheTestRootTheClassAndWhatMayBeASuite() {
        String path = CALCITE + "org/apache/ignite/internal/processors/query/calcite/exec/exp/"
            + "IgniteFunctionParameterTest.java";

        assertThat(TestSuites.testRoot(path)).contains("modules/calcite/src/test/java");
        assertThat(TestSuites.testRoot("modules/calcite/src/main/java/Foo.java")).isEmpty();
        assertThat(TestSuites.classOf(path))
            .isEqualTo("org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest");
        assertThat(List.of("a/UtilTestSuite.java", "a/IgniteCacheTestSuite2.java", "a/SuiteHelper.txt", "a/FooTest.java"))
            .filteredOn(TestSuites::maybeSuite).containsExactly("a/UtilTestSuite.java", "a/IgniteCacheTestSuite2.java");
    }
}
