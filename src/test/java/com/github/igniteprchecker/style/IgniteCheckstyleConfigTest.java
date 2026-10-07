package com.github.igniteprchecker.style;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The style autofix runs Ignite's own checkstyle.xml with the checkstyle jar this app ships. Ignite moved to
 * checkstyle 12.3.1 while the app still shipped 10.20.1. The copy of Ignite's config (master, 25.09.2026) in
 * test resources pins that the bundled checkstyle loads it and enforces its rules, so a version bump that
 * breaks the config fails here instead of in an author's PR.
 */
class IgniteCheckstyleConfigTest {
    private static final String PATH = "modules/core/src/main/java/org/apache/ignite/internal/sample/Sample.java";

    private static final String CLEAN = """
        package org.apache.ignite.internal.sample;

        import java.util.ArrayList;
        import java.util.List;

        /**
         * Sample.
         */
        public class Sample {
            /** Items. */
            private final List<String> items = new ArrayList<>();

            /**
             * @param item Item.
             */
            public void add(String item) {
                items.add(item);
            }

            /**
             * @return Size.
             */
            public int size() {
                return items.size();
            }
        }
        """;

    private CheckstyleRunner runner;

    @BeforeEach
    void igniteConfig() throws IOException {
        GithubClient github = mock(GithubClient.class);
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle.xml"))).thenReturn(resource("checkstyle.xml"));
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle-suppressions.xml")))
            .thenReturn(resource("checkstyle-suppressions.xml"));

        runner = new CheckstyleRunner(github, new GithubProperties("apache/ignite", null, null));
    }

    @Test
    void codeInIgniteStylePasses() throws Exception {
        CheckstyleRunner.CheckResult res = runner.check(Map.of(PATH, CLEAN));

        assertThat(res.violations()).isEmpty();
        assertThat(res.skipped()).isEmpty();
    }

    @Test
    void igniteRulesAreEnforced() throws Exception {
        String bad = CLEAN
            .replace("import java.util.ArrayList;\nimport java.util.List;", "import java.util.List;\nimport java.util.ArrayList;")
            .replace("    /** Items. */\n", "");

        CheckstyleRunner.CheckResult res = runner.check(Map.of(PATH, bad));

        assertThat(res.violations()).extracting(CheckstyleRunner.Violation::rule)
            .containsExactlyInAnyOrder("CustomImportOrder", "JavadocVariable");
        assertThat(res.skipped()).isEmpty();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = IgniteCheckstyleConfigTest.class.getResourceAsStream("/ignite-checkstyle/" + name)) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
