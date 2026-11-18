package com.github.igniteprchecker.style;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.StandingVisas;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The autofix pushes a commit to the author's branch, so a fix may change the spacing of the code and nothing else.
 * Under Ignite's own checkstyle.xml it wrote a space into a string literal on a line with a tab, as checkstyle counts
 * a tab as up to eight columns, and padded an operator after the next word, inside the literal that followed. A file a
 * fix left unparsable, as joining a declaration to the line comment after its {@code @Override} does, lost its
 * violations at the next check, which made it look fixed.
 */
class StyleFixKeepsCodeTest {
    private static final int PR = 13800;

    private static final String PATH = "modules/core/src/main/java/org/apache/ignite/internal/sample/Sample.java";

    /** A class in Ignite style but for the lines of its method, {@code %s}. */
    private static final String SAMPLE = """
        package org.apache.ignite.internal.sample;

        import java.util.HashMap;
        import java.util.Map;

        /**
         * Sample.
         */
        public class Sample {
            /** Map. */
            private final Map<String, String> map = new HashMap<>();

            /**
             * @param msg Message.
             * @return Text.
             */
        %s
        }
        """;

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas.GhActor author = new StandingVisas.GhActor("author", "tc", "gh-pat", null);

    @BeforeEach
    void igniteConfig() throws IOException {
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle.xml"))).thenReturn(resource("checkstyle.xml"));
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle-suppressions.xml")))
            .thenReturn(resource("checkstyle-suppressions.xml"));
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("Author", "author/ignite", "ignite-28867", "f195e0c"));
        when(github.prJavaFiles(PR)).thenReturn(List.of(PATH));
        when(github.commitFiles(anyString(), anyString(), anyString(), anyString(), anyMap(), anyString()))
            .thenReturn("41b0d3a5c9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4");
    }

    @Test
    void aLineWithTabsKeepsItsStringLiteral() {
        String pushed = pushed("""
                public String text(String msg) {
            \t\tmap.put("k","some long text here");

                    return msg;
                }
            """);

        assertThat(pushed).contains("\n        map.put(\"k\", \"some long text here\");\n");
    }

    /** Given either JVM language, when the operator is fixed, then its padding leaves both string literals intact. */
    @ParameterizedTest
    @ValueSource(strings = {"en", "ru"})
    void anOperatorIsPaddedNextToItselfNotInsideTheLiteralAfterIt(String language) {
        Locale previous = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(language));
            String pushed = pushed("""
                    public String text(String msg) {
                        return "Failed to do: "+msg+" (will retry later)";
                    }
                """);

            assertThat(pushed).contains("        return \"Failed to do: \" + msg + \" (will retry later)\";\n");
        }
        finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test
    void aFileTheFixLeftUnreadableIsNotPushed() throws Exception {
        CheckstyleRunner.Violation order = new CheckstyleRunner.Violation(PATH, 18, 5, "ModifierOrder", "order");
        CheckstyleRunner checkstyle = mock(CheckstyleRunner.class);
        when(checkstyle.check(anyMap())).thenReturn(new CheckstyleRunner.CheckResult(List.of(order), List.of()),
            new CheckstyleRunner.CheckResult(List.of(), List.of(PATH)),
            new CheckstyleRunner.CheckResult(List.of(order), List.of()));

        String note = fix("""
                final public String text(String msg) {
                    return msg;
                }
            """, checkstyle);

        assertThat(note).contains("1 violation in the changed files, not mechanically fixable");
        verify(github, never()).commitFiles(anyString(), anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    /** The file as the autofix pushed it, when its method has these lines. */
    private String pushed(String method) {
        fix(method, checkstyle());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
        verify(github).commitFiles(eq("gh-pat"), eq("author/ignite"), eq("ignite-28867"), eq("f195e0c"), files.capture(),
            anyString());

        return files.getValue().get(PATH);
    }

    private String fix(String method, CheckstyleRunner checkstyle) {
        when(github.rawFileUpTo("author/ignite", "f195e0c", PATH, CheckstyleRunner.MAX_FILE_BYTES))
            .thenReturn(Optional.of(SAMPLE.formatted(method.stripTrailing())));

        return new StyleFixService(github, checkstyle).fixForCommand(PR, author, "Author");
    }

    private CheckstyleRunner checkstyle() {
        return new CheckstyleRunner(github, new GithubProperties("apache/ignite", null, null));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = StyleFixKeepsCodeTest.class.getResourceAsStream("/ignite-checkstyle/" + name)) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
