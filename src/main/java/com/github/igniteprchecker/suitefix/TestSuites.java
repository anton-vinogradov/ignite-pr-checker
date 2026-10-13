package com.github.igniteprchecker.suitefix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ignite's test suites as their files list classes, and the edit that lists one more. Suites come in three forms:
 * {@code @Suite.SuiteClasses({ A.class, ... })}, one entry a line; and dynamic ones that build a list,
 * {@code GridTestUtils.addTestIfNeeded(suite, A.class, ignoredTests);} or {@code suite.add(A.class);}. A class is
 * added by copying the line of a class the suite already lists, its neighbour, with the name changed, so the file
 * keeps its own form and style. Only a line that lists one class and nothing else can be copied: a line of several
 * entries, one that opens the list, or one of a list of classes to leave out would copy more than the class.
 */
final class TestSuites {
    /** A class reference, {@code Foo.class} or {@code org.apache.Foo.class}: a nested class is not one. */
    private static final Pattern CLASS_REF =
        Pattern.compile("(?<![\\w$.])((?:[a-z_][\\w$]*\\.)*)([A-Z_$][\\w$]*)\\.class\\b");

    /** An entry of an array of classes on a line of its own, its comma and comment apart. */
    private static final Pattern ELEMENT =
        Pattern.compile("^(\\s*(?:[a-z_][\\w$]*\\.)*[A-Z_$][\\w$]*\\.class)(\\s*,)?\\s*(?://.*)?$");

    /** Any entry of an array of classes, a nested class's too: what lies between an entry and its list's opening. */
    private static final Pattern ANY_ELEMENT = Pattern.compile("^[\\w$.]+\\.class\\s*,?\\s*(?://.*)?$");

    /** A statement that adds one class to a suite's list, its comment apart. */
    private static final Pattern ADD = Pattern.compile(
        "^(\\s*(?:([\\w$.]+)\\.)?(?:add|addTest|addTestSuite|addTestIfNeeded)\\(.*?\\);)\\s*(?://.*)?$");

    /** A list of classes that are left out rather than run. */
    private static final Pattern LEFT_OUT = Pattern.compile("(?i)ignor|skip|exclud");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+([\\w.]+)\\s*;");

    private static final Pattern WILDCARD_IMPORT = Pattern.compile("^import\\s+[\\w.]+\\.\\*\\s*;");

    private static final String TEST_ROOT = "/src/test/java/";

    private TestSuites() {
    }

    /** The test sources a class's file is under, as {@code modules/calcite/src/test/java}; empty for another path. */
    static Optional<String> testRoot(String path) {
        int at = path.indexOf(TEST_ROOT);

        return at <= 0 ? Optional.empty() : Optional.of(path.substring(0, at + TEST_ROOT.length() - 1));
    }

    /** Whether a file may be a suite: Ignite names them so, as UtilTestSuite, IgniteCacheTestSuite2. */
    static boolean maybeSuite(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);

        return name.endsWith(".java") && name.contains("Suite");
    }

    /** The class a file under {@code src/test/java} holds, by its path. */
    static String classOf(String path) {
        String noExt = path.endsWith(".java") ? path.substring(0, path.length() - ".java".length()) : path;
        int root = noExt.indexOf(TEST_ROOT);

        return (root >= 0 ? noExt.substring(root + TEST_ROOT.length()) : noExt).replace('/', '.');
    }

    /** The suite a file is: empty when no line of it lists a class the way a suite does. */
    static Optional<Suite> read(String path, String source) {
        Lines lines = new Lines(source);
        Map<String, String> members = new LinkedHashMap<>();
        for (Entry e : lines.entries())
            members.putIfAbsent(e.name(), lines.fqcnOf(e));

        return members.isEmpty() ? Optional.empty()
            : Optional.of(new Suite(path, lines.pkg(), members.entrySet().stream()
                .map(m -> new Member(m.getKey(), m.getValue())).toList()));
    }

    /**
     * The suite's source with class {@code fqcn} listed next to {@code neighbour}, its line a copy of the neighbour's,
     * and imported when the file does not reach it otherwise; or why it can't be.
     */
    static Edit add(String source, String neighbour, String fqcn) {
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        Lines lines = new Lines(source);
        List<Entry> listed = lines.entries();
        if (listed.stream().anyMatch(e -> e.name().equals(simple)))
            return Edit.failed("it already lists `" + simple + "`");

        List<Entry> at = listed.stream().filter(e -> e.name().equals(neighbour)).toList();
        if (at.isEmpty())
            return Edit.failed("it does not list `" + neighbour + "` on a line of its own");
        if (at.size() > 1)
            return Edit.failed("it lists `" + neighbour + "` on " + at.size() + " lines");

        Entry next = at.get(0);
        String pkg = fqcn.contains(".") ? fqcn.substring(0, fqcn.lastIndexOf('.')) : "";
        boolean reached = pkg.equals(lines.pkg()) || lines.imports().contains(fqcn);
        boolean clash = lines.imports().stream().anyMatch(i -> i.endsWith("." + simple));
        boolean qualified = !next.qualifier().isEmpty() || !reached && clash;

        List<String> out = new ArrayList<>(lines.all());
        out.add(next.before() ? next.index() : next.index() + 1, next.copyFor(qualified ? fqcn : simple));
        if (!qualified && !reached)
            Lines.importAt(out, fqcn);

        return new Edit(String.join("\n", out), null);
    }

    /** A suite: its file, its package, and the classes it lists on lines of their own, each listed once. */
    record Suite(String path, String pkg, List<Member> members) {
        /** The suite's class name, as people call it. */
        String name() {
            return path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
        }
    }

    /** A class a suite lists, by the name its file uses, and its full name; null when the file does not tell. */
    record Member(String name, String fqcn) {
    }

    /** A suite's new source, or why the class could not be added ({@code failure}). */
    record Edit(String source, String failure) {
        static Edit failed(String why) {
            return new Edit(null, why);
        }
    }

    /**
     * A line that lists one class: its index, the class as written ({@code qualifier} is the package before the name,
     * empty for a bare name, and {@code refStart}/{@code refEnd} its place in {@code kept}), and the line up to the
     * end of the entry. An array entry ends with a comma, put back by {@code tail}; {@code before}: the copy goes
     * above, as a last entry with no comma takes one above it, not below.
     */
    private record Entry(int index, String qualifier, String name, int refStart, int refEnd, String kept,
        String tail, boolean before) {
        /** The line listing {@code ref} instead, without the neighbour's comment. */
        String copyFor(String ref) {
            return kept.substring(0, refStart) + ref + ".class" + kept.substring(refEnd) + tail;
        }
    }

    /** A source as lines, with the entries that list a class, its package and imports. */
    private static final class Lines {
        private final List<String> all;

        private final boolean[] commented;

        private final List<String> imports = new ArrayList<>();

        private String pkg = "";

        private boolean wildcards;

        Lines(String source) {
            all = List.of(source.split("\n", -1));
            commented = new boolean[all.size()];
            boolean inBlock = false;
            for (int i = 0; i < all.size(); i++) {
                String line = all.get(i);
                commented[i] = inBlock || line.contains("/*");
                inBlock = blockOpenAfter(line, inBlock);

                Matcher p = PACKAGE.matcher(line);
                if (pkg.isEmpty() && p.find())
                    pkg = p.group(1);
                Matcher imp = IMPORT.matcher(line);
                if (imp.find())
                    imports.add(imp.group(1));
                wildcards |= WILDCARD_IMPORT.matcher(line).find();
            }
        }

        /** Whether a block comment is still open after {@code line}. */
        private static boolean blockOpenAfter(String line, boolean open) {
            int pos = 0;
            while (true) {
                if (open) {
                    int close = line.indexOf("*/", pos);
                    if (close < 0)
                        return true;
                    open = false;
                    pos = close + 2;
                }
                else {
                    int start = line.indexOf("/*", pos);
                    int lineComment = line.indexOf("//", pos);
                    if (start < 0 || lineComment >= 0 && lineComment < start)
                        return false;
                    open = true;
                    pos = start + 2;
                }
            }
        }

        List<String> all() {
            return all;
        }

        String pkg() {
            return pkg;
        }

        List<String> imports() {
            return imports;
        }

        /** The lines that list one class each and nothing else, in a suite's list. */
        List<Entry> entries() {
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                if (commented[i])
                    continue;

                String raw = all.get(i);
                String cr = raw.endsWith("\r") ? "\r" : "";
                String line = raw.substring(0, raw.length() - cr.length());
                Matcher element = ELEMENT.matcher(line);
                Matcher add = ADD.matcher(line);
                Optional<Entry> entry = Optional.empty();
                if (element.matches() && inList(i))
                    entry = entry(i, element.group(1), "," + cr, element.group(2) == null);
                else if (add.matches() && (add.group(2) == null || !LEFT_OUT.matcher(add.group(2)).find()))
                    entry = entry(i, add.group(1), cr, false);
                entry.ifPresent(out::add);
            }

            return out;
        }

        /** The entry of line {@code i}, written up to its end as {@code kept}, when it names exactly one class. */
        private static Optional<Entry> entry(int i, String kept, String tail, boolean before) {
            Matcher ref = CLASS_REF.matcher(kept);
            if (!ref.find())
                return Optional.empty();

            String qualifier = ref.group(1);
            Entry entry = new Entry(i, qualifier.isEmpty() ? "" : qualifier.substring(0, qualifier.length() - 1),
                ref.group(2), ref.start(), ref.end(), kept, tail, before);

            return ref.find() ? Optional.empty() : Optional.of(entry);
        }

        /**
         * Whether line {@code i}, an array entry, is in a list a suite runs: the line that opens its list, above the
         * other entries, blank lines and comments, ends with "{" or "(" and is not a list of classes left out.
         */
        private boolean inList(int i) {
            for (int j = i - 1; j >= 0; j--) {
                String line = all.get(j).strip();
                if (line.isEmpty() || commented[j] || line.startsWith("//") || ANY_ELEMENT.matcher(line).matches())
                    continue;

                String code = line.replaceFirst("\\s*//.*$", "");

                return (code.endsWith("{") || code.endsWith("(")) && !LEFT_OUT.matcher(code).find();
            }

            return false;
        }

        /** The full name of the class an entry names: by its package, an import, or the suite's own package. */
        String fqcnOf(Entry e) {
            if (!e.qualifier().isEmpty())
                return e.qualifier() + "." + e.name();

            Optional<String> imported = imports.stream().filter(i -> i.endsWith("." + e.name())).findFirst();
            if (imported.isPresent())
                return imported.get();

            return wildcards ? null : pkg.isEmpty() ? e.name() : pkg + "." + e.name();
        }

        /**
         * Puts {@code import fqcn;} among the imports in {@code out} where the order of names has it, after the
         * package line when there are none.
         */
        static void importAt(List<String> out, String fqcn) {
            String line = "import " + fqcn + ";";
            int last = -1;
            for (int i = 0; i < out.size(); i++) {
                Matcher imp = IMPORT.matcher(out.get(i));
                if (!imp.find())
                    continue;
                if (imp.group(1).compareTo(fqcn) > 0) {
                    out.add(i, line);

                    return;
                }
                last = i;
            }
            if (last >= 0) {
                out.add(last + 1, line);

                return;
            }

            for (int i = 0; i < out.size(); i++) {
                if (PACKAGE.matcher(out.get(i)).find()) {
                    out.addAll(i + 1, List.of("", line));

                    return;
                }
            }
            out.add(0, line);
        }
    }
}
