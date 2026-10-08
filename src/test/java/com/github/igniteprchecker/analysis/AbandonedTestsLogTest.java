package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Ignite's job "Check java code on JDK 17" fails its step "Run abandoned tests checks." on a test class no suite holds,
 * and only its log names the class: had PR 13335 left SslRenewalTest and SslContextReloadTest out of
 * SecurityTestSuite, the log would read as the fragment in {@code actions/abandoned-tests-failed.log}, in the format of
 * GitHub's job logs and of Ignite's AssertOnOrphanedTests run by the exec plugin.
 */
class AbandonedTestsLogTest {
    private static final Path LOG = Path.of("src/test/resources/actions/abandoned-tests-failed.log");

    @Test
    void theClassesInNoSuiteAreReadFromTheJobsLog() throws IOException {
        try (Stream<String> lines = Files.lines(LOG, StandardCharsets.UTF_8)) {
            assertThat(AbandonedTestsCheck.nonSuited(lines)).hasValueSatisfying(classes -> assertThat(classes)
                .containsExactly("org.apache.ignite.ssl.SslContextReloadTest",
                    "org.apache.ignite.internal.ssl.SslRenewalTest"));
        }
    }

    /**
     * An excerpt of a real log: job 110332358026 of apache/ignite, 1 Oct 2026, where IgniteFunctionParameterTest was
     * in no suite. The class shows twice, in the assertion and in Maven's error after it.
     */
    @Test
    void aRealJobLogNamesItsClassOnce() throws IOException {
        try (Stream<String> lines = Files.lines(Path.of("src/test/resources/actions/abandoned-tests-failed-real.log"),
            StandardCharsets.UTF_8)) {
            assertThat(AbandonedTestsCheck.nonSuited(lines)).hasValueSatisfying(classes -> assertThat(classes)
                .containsExactly("org.apache.ignite.internal.processors.query.calcite.exec.exp.IgniteFunctionParameterTest"));
        }
    }

    /** A stack trace's "\tat …" lines elsewhere in the log are no class names. */
    @Test
    void aLogWithoutTheListNamesNoClass() throws IOException {
        try (Stream<String> lines = Files.lines(LOG, StandardCharsets.UTF_8)) {
            assertThat(AbandonedTestsCheck.nonSuited(lines.filter(l -> !l.contains("non-suited")))).isEmpty();
        }
    }

    /** The warning's list lost a line; Maven's error at the end repeats the list whole. */
    @Test
    void aListCutShortIsPassedOverForTheWholeOne() {
        Stream<String> lines = Stream.of(
            "2026-09-29T14:46:58.0103602Z List of non-suited classes (2 items):",
            "2026-09-29T14:46:58.0103711Z \torg.apache.ignite.ssl.SslContextReloadTest",
            "2026-09-29T14:46:58.0103899Z ",
            "2026-09-29T14:46:58.0213330Z [ERROR] List of non-suited classes (2 items):",
            "2026-09-29T14:46:58.0213447Z [ERROR] \torg.apache.ignite.ssl.SslContextReloadTest",
            "2026-09-29T14:46:58.0213563Z [ERROR] \torg.apache.ignite.ssl.SslContextReloadTest$Nested");

        assertThat(AbandonedTestsCheck.nonSuited(lines)).hasValueSatisfying(classes -> assertThat(classes)
            .containsExactly("org.apache.ignite.ssl.SslContextReloadTest",
                "org.apache.ignite.ssl.SslContextReloadTest$Nested"));
    }
}
