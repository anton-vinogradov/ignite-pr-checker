package com.github.igniteprchecker.style;

import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.github.GithubClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The check -> fix -> re-check loop on a synthetic file, with a config covering the fixable rules. */
class StyleFixTest {
    private static final String CONFIG = """
        <?xml version="1.0"?>
        <!DOCTYPE module PUBLIC "-//Puppy Crawl//DTD Check Configuration 1.3//EN"
            "http://www.puppycrawl.com/dtds/configuration_1_3.dtd">
        <module name="Checker">
            <property name="charset" value="UTF-8"/>
            <module name="NewlineAtEndOfFile"/>
            <module name="FileTabCharacter"><property name="eachLine" value="true"/></module>
            <module name="TreeWalker">
                <module name="RedundantImport"/>
                <module name="UnusedImports"/>
                <module name="CustomImportOrder">
                    <property name="customImportOrderRules"
                              value="STANDARD_JAVA_PACKAGE###SPECIAL_IMPORTS###THIRD_PARTY_PACKAGE###STATIC"/>
                    <property name="standardPackageRegExp" value="^java\\."/>
                    <property name="specialImportsRegExp" value="^(javax|jakarta)\\."/>
                    <property name="sortImportsInGroupAlphabetically" value="true"/>
                    <property name="separateLineBetweenGroups" value="false"/>
                </module>
                <module name="WhitespaceAfter"><property name="tokens" value="COMMA, SEMI"/></module>
                <module name="ModifierOrder"/>
            </module>
        </module>
        """;

    private static final String BAD = """
        package p;

        import java.util.Map;
        import java.util.List;
        import java.util.ArrayList;

        /** C. */
        public class C {
            /** F. */
            static public final int F = 1;

            /** M. */
            public List<Map<String, String>> m(int a,int b) {
                List<Map<String, String>> l = new ArrayList<>();
                return l;
            }
        }""";

    @Test
    void fixesMechanicalViolations() throws Exception {
        GithubClient github = mock(GithubClient.class);
        when(github.rawFile(anyString(), anyString(), contains("suppressions")))
            .thenReturn("<?xml version=\"1.0\"?><!DOCTYPE suppressions PUBLIC "
                + "\"-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN\" "
                + "\"https://checkstyle.org/dtds/suppressions_1_2.dtd\"><suppressions/>");
        when(github.rawFile(anyString(), anyString(), eq("checkstyle/checkstyle.xml"))).thenReturn(CONFIG);

        CheckstyleRunner runner = new CheckstyleRunner(github, new GithubProperties("apache/ignite", null, null));

        String path = "modules/core/src/main/java/p/C.java";
        List<CheckstyleRunner.Violation> before = runner.check(Map.of(path, BAD));
        assertFalse(before.isEmpty(), "the synthetic file must violate: " + before);

        String fixed = Fixers.apply(BAD, before);
        List<CheckstyleRunner.Violation> after = runner.check(Map.of(path, fixed));

        assertEquals(List.of(), after, "everything in the synthetic file is mechanically fixable");
        assertTrue(fixed.contains("public static final int F"), "modifiers reordered");
        assertTrue(fixed.indexOf("java.util.ArrayList") < fixed.indexOf("java.util.List"), "imports sorted");
        assertTrue(fixed.contains("int a, int b"), "space after comma");
        assertTrue(fixed.endsWith("\n"), "newline at EOF");
    }
}
