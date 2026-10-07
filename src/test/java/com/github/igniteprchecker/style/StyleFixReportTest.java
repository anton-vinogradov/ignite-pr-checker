package com.github.igniteprchecker.style;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.StandingVisas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The autofix report said "13 remain — the Checkstyle suite may still fail": no list of what remained, a suite
 * RunAll does not have, and no word that a commit had just landed on the author's branch. And it downloaded every
 * changed file whole before looking at its size.
 */
class StyleFixReportTest {
    private static final int PR = 13800;

    private static final String PATH = "modules/core/src/main/java/p/C.java";

    private static final String CONFIG = """
        <?xml version="1.0"?>
        <!DOCTYPE module PUBLIC "-//Puppy Crawl//DTD Check Configuration 1.3//EN"
            "http://www.puppycrawl.com/dtds/configuration_1_3.dtd">
        <module name="Checker">
            <property name="charset" value="UTF-8"/>
            <module name="TreeWalker">
                <module name="ModifierOrder"/>
                <module name="MethodName"/>
            </module>
        </module>
        """;

    private static final String FIXABLE = """
        package p;

        /** C. */
        public class C {
            /** F. */
            static public final int F = 1;
        }
        """;

    private static final String NOT_FIXABLE = """
        package p;

        /** C. */
        public class C {
            /** M. */
            public void Bad_name() {
            }
        }
        """;

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas.GhActor author = new StandingVisas.GhActor("author", "tc", "gh-pat", null);

    @BeforeEach
    void setUp() {
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle.xml"))).thenReturn(CONFIG);
        when(github.rawFile(anyString(), anyString(), contains("suppressions")))
            .thenReturn("<?xml version=\"1.0\"?><!DOCTYPE suppressions PUBLIC "
                + "\"-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN\" "
                + "\"https://checkstyle.org/dtds/suppressions_1_2.dtd\"><suppressions/>");
        when(github.prHead(PR)).thenReturn(new GithubClient.PrHead("Author", "author/ignite", "ignite-28867", "f195e0c"));
        when(github.checksUrl(PR)).thenReturn("https://github.com/apache/ignite/pull/13800/checks");
        when(github.commitFiles(eq("gh-pat"), eq("author/ignite"), eq("ignite-28867"), eq("f195e0c"), anyMap(),
            anyString())).thenReturn("41b0d3a5c9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4");
    }

    @Test
    void aFixSaysWhatItPushedWhereAndListsWhatIsLeft() {
        changed(Map.of(PATH, FIXABLE.replace("    /** F. */", "    /** M. */\n    public void Bad_name() {\n    }\n\n"
            + "    /** F. */")));

        String note = service().fixForCommand(PR, author, "Author");

        assertThat(note).isEqualTo("🎨 _Checkstyle autofix: fixed 1 of 2 violation(s) in 1 file(s) and pushed `41b0d3a`"
            + " to your branch `ignite-28867` — `git pull` before your next push. 1 remain, which need a human (javadoc,"
            + " naming, wrapping): the [**Check java code**](https://github.com/apache/ignite/pull/13800/checks) check"
            + " of this PR may still fail on them._\n\n<details><summary>1 violation(s) left</summary>\n\n"
            + "- `" + PATH + ":6` — MethodName\n\n</details>");
    }

    @Test
    void nothingFixableIsListedAndNotCalledACertainFailure() {
        changed(Map.of(PATH, NOT_FIXABLE));

        String note = service().fixForCommand(PR, author, "Author");

        assertThat(note).isEqualTo("🎨 _Checkstyle: 1 violation(s) in the changed files, none of them mechanically"
            + " fixable (javadoc, naming, wrapping need a human): the [**Check java code**](https://github.com/apache/"
            + "ignite/pull/13800/checks) check of this PR may fail on them._\n\n<details><summary>1 violation(s) left"
            + "</summary>\n\n- `" + PATH + ":6` — MethodName\n\n</details>");
        verify(github, never()).commitFiles(anyString(), anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void aHugeFileIsNotDownloadedWhole() {
        when(github.prJavaFiles(PR)).thenReturn(List.of(PATH));
        when(github.rawFileUpTo("author/ignite", "f195e0c", PATH, CheckstyleRunner.MAX_FILE_BYTES))
            .thenReturn(Optional.empty());

        String note = service().fixForCommand(PR, author, "Author");

        assertThat(note).isEqualTo("🎨 _Checkstyle: 1 file(s) were too large for the checker and were not checked ("
            + PATH + ")._");
        verify(github, never()).rawFile(anyString(), anyString(), eq(PATH));
    }

    /** Fifty changed files of 300 KB each would be 15 MB in a 512 MB heap, before checkstyle builds a tree of one. */
    @Test
    void allFilesTogetherAreReadUpToFiveMegabytes() throws Exception {
        List<String> paths = IntStream.range(0, 20).mapToObj(i -> "modules/core/src/main/java/p/C" + i + ".java").toList();
        when(github.prJavaFiles(PR)).thenReturn(paths);
        when(github.rawFileUpTo(eq("author/ignite"), eq("f195e0c"), anyString(), anyInt())).thenAnswer(inv ->
            inv.getArgument(3, Integer.class) < 300_000 ? Optional.empty() : Optional.of("x".repeat(300_000)));
        CheckstyleRunner checkstyle = mock(CheckstyleRunner.class);
        when(checkstyle.check(anyMap())).thenReturn(new CheckstyleRunner.CheckResult(List.of(), List.of()));

        String note = new StyleFixService(github, checkstyle).fixForCommand(PR, author, "Author");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> checked = ArgumentCaptor.forClass(Map.class);
        verify(checkstyle).check(checked.capture());
        assertThat(checked.getValue()).hasSize(16);
        assertThat(note).isEqualTo("🎨 _Checkstyle: 4 file(s) were too large for the checker and were not checked ("
            + paths.get(16) + ", …)._");
        ArgumentCaptor<Integer> asked = ArgumentCaptor.forClass(Integer.class);
        verify(github, atLeastOnce()).rawFileUpTo(anyString(), anyString(), anyString(), asked.capture());
        assertThat(asked.getAllValues()).allMatch(max -> max <= CheckstyleRunner.MAX_FILE_BYTES);
        assertThat(asked.getAllValues().subList(16, 20)).containsOnly(200_000);
    }

    private void changed(Map<String, String> files) {
        when(github.prJavaFiles(PR)).thenReturn(new ArrayList<>(files.keySet()));
        files.forEach((path, content) -> when(github.rawFileUpTo("author/ignite", "f195e0c", path,
            CheckstyleRunner.MAX_FILE_BYTES)).thenReturn(Optional.of(content)));
    }

    private StyleFixService service() {
        return new StyleFixService(github, new CheckstyleRunner(github, new GithubProperties("apache/ignite", null,
            null)));
    }
}
