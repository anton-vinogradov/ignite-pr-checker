package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.model.TestVerdict;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The failed tests a verdict names, grouped by the suite run they failed in and their class, the biggest group first:
 * 489 failures of one class read as one line instead of ten lines of package names and "… and 479 more". Each line
 * names the test as {@code Class.method}, says in a few words why it is listed, and links the run in TeamCity.
 */
final class TestGroups {
    /** Groups a PR comment shows; the rest fold under a summary. */
    static final int SHOWN_MARKDOWN = 5;

    /** Groups a visa shows: JIRA cannot fold, so the rest are counted and left to the checker's page. */
    static final int SHOWN_WIKI = 10;

    /** Groups a PR comment lists at all, folded ones included; the checker's page has the rest. */
    static final int LISTED_MARKDOWN = 50;

    /**
     * Characters the lines of one list of a PR comment take at most: GitHub refuses a comment over 65536, a comment
     * holds three lists, and parameterized test names run to hundreds of characters a line.
     */
    static final int LISTED_CHARS = 15_000;

    /** How many methods of a class a group spells out. */
    private static final int METHODS_NAMED = 3;

    /** Characters JIRA reads as markup inside {@code {{…}}}: a parameterized name became a link or a broken macro. */
    private static final Pattern WIKI_MARKUP = Pattern.compile("[\\[\\]{}\\\\]");

    private final String tcBase;

    private final String pageUrl;

    /** {@code tcBase} ends with a slash; {@code pageUrl} is the checker's page of the PR. */
    TestGroups(String tcBase, String pageUrl) {
        this.tcBase = tcBase;
        this.pageUrl = pageUrl;
    }

    /** The list in GitHub's markdown, one line per group; {@code why}: each lone test says why it is listed. */
    String markdown(List<TestVerdict> tests, boolean why) {
        List<Group> groups = group(tests);
        StringBuilder b = new StringBuilder();
        int listed = 0;
        int room = LISTED_CHARS;
        for (Group g : groups) {
            String line = markdownLine(g, why);
            if (listed == LISTED_MARKDOWN || line.length() > room)
                break;
            if (listed == SHOWN_MARKDOWN)
                b.append("<details><summary>").append(more(groups.subList(listed, groups.size())))
                    .append("</summary>\n\n");
            b.append(line);
            room -= line.length();
            listed++;
        }
        if (groups.size() > listed)
            b.append("- … and ").append(more(groups.subList(listed, groups.size())))
                .append(" on [the checker's page](").append(pageUrl).append(")\n");
        if (listed > SHOWN_MARKDOWN)
            b.append("\n</details>\n");

        return b.toString();
    }

    /** The same in JIRA's wiki markup: the first groups, and how many more are on the checker's page. */
    String wiki(List<TestVerdict> tests, boolean why) {
        List<Group> groups = group(tests);
        StringBuilder b = new StringBuilder();
        groups.stream().limit(SHOWN_WIKI).forEach(g -> b.append(wikiLine(g, why)));
        if (groups.size() > SHOWN_WIKI)
            b.append("… and ").append(more(groups.subList(SHOWN_WIKI, groups.size())))
                .append(" on [the checker's page|").append(pageUrl).append("]\n");

        return b.toString();
    }

    private String markdownLine(Group g, boolean why) {
        String url = g.url(tcBase);
        if (g.tests().size() == 1) {
            TestVerdict t = g.tests().get(0);
            Name n = Name.of(t.name());
            String reason = why ? shortReason(t) : "";

            return "- " + g.suiteName() + " · " + code(n.shown()) + (n.classLevel() ? " (class-level failure)" : "")
                + (reason.isEmpty() ? "" : " — " + reason) + (url == null ? "" : " · [TC](" + testUrl(url, t) + ")")
                + "\n";
        }

        List<String> methods = g.tests().stream().limit(METHODS_NAMED).map(t -> Name.of(t.name()))
            .map(n -> n.classLevel() ? "(class-level failure)" : code(n.method())).toList();

        return "- " + g.suiteName() + " · " + code(g.className()) + " — " + g.tests().size() + " tests: "
            + String.join(", ", methods) + rest(g) + (url == null ? "" : " · [TC](" + url + ")") + "\n";
    }

    private String wikiLine(Group g, boolean why) {
        String url = g.url(tcBase);
        if (g.tests().size() == 1) {
            TestVerdict t = g.tests().get(0);
            Name n = Name.of(t.name());
            String reason = why ? shortReason(t) : "";

            return "- " + g.suiteName() + " · {{" + wikiText(n.shown()) + "}}"
                + (n.classLevel() ? " (class-level failure)" : "") + (reason.isEmpty() ? "" : " — " + reason)
                + (url == null ? "" : " · [TC|" + testUrl(url, t) + "]") + "\n";
        }

        List<String> methods = g.tests().stream().limit(METHODS_NAMED).map(t -> Name.of(t.name()))
            .map(n -> n.classLevel() ? "(class-level failure)" : "{{" + wikiText(n.method()) + "}}").toList();

        return "- " + g.suiteName() + " · {{" + wikiText(g.className()) + "}} — " + g.tests().size() + " tests: "
            + String.join(", ", methods) + rest(g) + (url == null ? "" : " · [TC|" + url + "]") + "\n";
    }

    private static String rest(Group g) {
        int left = g.tests().size() - METHODS_NAMED;

        return left > 0 ? " and " + left + " more" : "";
    }

    /** "37 more test(s) in 4 class(es)" for the groups left out. */
    private static String more(List<Group> left) {
        int tests = left.stream().mapToInt(g -> g.tests().size()).sum();

        return tests + " more test(s) in " + left.size() + " class(es)";
    }

    /** The test's own row in the run's Tests tab, opened. */
    private static String testUrl(String runUrl, TestVerdict t) {
        return runUrl + (t.occurrenceId() == null ? "" : "&expandedTest=" + enc(t.occurrenceId()))
            + "#testNameId" + Long.toUnsignedString(t.testId());
    }

    /** The tests by suite run and class, in the order they came, the biggest group first. */
    static List<Group> group(List<TestVerdict> tests) {
        Map<String, List<TestVerdict>> byKey = new LinkedHashMap<>();
        for (TestVerdict t : tests) {
            Name n = Name.of(t.name());
            String key = t.suiteBuildId() + "|" + t.suite() + "|" + (n.cls() == null ? t.name() : n.cls());
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<Group> out = new ArrayList<>();
        for (List<TestVerdict> same : byKey.values()) {
            TestVerdict first = same.get(0);
            Name n = Name.of(first.name());
            out.add(new Group(first.suiteName() == null ? first.suite() : first.suiteName(), first.suite(),
                first.suiteBuildId(), n.cls() == null ? n.shown() : n.cls(), List.copyOf(same)));
        }
        out.sort(Comparator.comparingInt((Group g) -> g.tests().size()).reversed());

        return out;
    }

    /**
     * Why a test is listed, in a few words: how many runs of the code under review it failed, and that master never
     * ran it when it did not. A verdict that rests on one run says so.
     */
    static String shortReason(TestVerdict t) {
        String runs = t.branchRuns() == null ? "" : t.branchRuns();
        String code = t.codeRuns() > 0 && t.codeRuns() < runs.length() ? runs.substring(runs.length() - t.codeRuns())
            : runs;
        long failed = code.chars().filter(c -> c == 'F').count();
        List<String> parts = new ArrayList<>();
        if (code.length() == 1)
            parts.add("one run of this code");
        else if (!code.isEmpty())
            parts.add("failed " + failed + " of " + code.length() + " runs of this code");
        if (t.reason() != null && t.reason().startsWith("no master history"))
            parts.add("no master history");

        return String.join("; ", parts);
    }

    /** Text for inside JIRA's {@code {{…}}}, its markup characters escaped so a test name reads as written. */
    static String wikiText(String s) {
        return WIKI_MARKUP.matcher(s).replaceAll(m -> Matcher.quoteReplacement("\\" + m.group()));
    }

    /** A markdown code span that holds {@code s} whole, backticks in it included. */
    private static String code(String s) {
        return s.contains("`") ? "`` " + s + " ``" : "`" + s + "`";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** One suite run's failed tests of one class; {@code className} is the whole short name of a lone unparsed one. */
    record Group(String suiteName, String suite, long suiteBuildId, String className, List<TestVerdict> tests) {
        /** The run's Tests tab in TeamCity; null when the run is not known. */
        String url(String tcBase) {
            return suite == null || suiteBuildId == 0 ? null
                : tcBase + "buildConfiguration/" + enc(suite) + "/" + suiteBuildId + "?buildTab=tests";
        }
    }

    /**
     * A TeamCity test name without its suite prefix and package: {@code cls} and {@code method}, a dot inside
     * {@code (…)} or {@code […]} kept. A failure of the class itself ("pkg.SomeTest.") has no method; a name
     * with no dot at all has no class.
     */
    record Name(String cls, String method) {
        static Name of(String name) {
            String s = name.contains(": ") ? name.substring(name.indexOf(": ") + 2) : name;
            boolean classLevel = s.endsWith(".");
            List<String> segs = new ArrayList<>();
            StringBuilder seg = new StringBuilder();
            int depth = 0;
            for (char ch : s.toCharArray()) {
                if (ch == '(' || ch == '[')
                    depth++;
                else if (ch == ')' || ch == ']')
                    depth = Math.max(0, depth - 1);
                if (ch == '.' && depth == 0) {
                    segs.add(seg.toString());
                    seg.setLength(0);
                }
                else
                    seg.append(ch);
            }
            segs.add(seg.toString());
            segs.removeIf(String::isEmpty);
            if (segs.isEmpty())
                return new Name(null, s);
            if (classLevel)
                return new Name(segs.get(segs.size() - 1), null);

            return segs.size() == 1 ? new Name(null, segs.get(0))
                : new Name(segs.get(segs.size() - 2), segs.get(segs.size() - 1));
        }

        boolean classLevel() {
            return method == null;
        }

        /** "Class.method", "Class" for a class-level failure, or the method alone. */
        String shown() {
            return cls == null ? method : method == null ? cls : cls + "." + method;
        }
    }
}
