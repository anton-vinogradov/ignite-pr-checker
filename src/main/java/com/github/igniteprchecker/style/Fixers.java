package com.github.igniteprchecker.style;

import com.github.igniteprchecker.style.CheckstyleRunner.Violation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mechanical checkstyle fixes — only edits whose correctness is self-evident from the rule itself
 * (a removed unused import, a reordered import block, whitespace). Anything needing judgement
 * (javadoc text, line wrapping, naming) is deliberately NOT here: that is the LLM/human tail.
 * Line/column numbers come from checkstyle (1-based); fixes are applied bottom-up so earlier
 * positions stay valid.
 */
final class Fixers {
    /** JLS modifier order, as ModifierOrder suggests. */
    private static final List<String> MODIFIER_ORDER = List.of(
        "public", "protected", "private", "abstract", "default", "static", "final", "transient",
        "volatile", "synchronized", "native", "strictfp");

    private Fixers() {
    }

    /**
     * Applies what it can of the file's violations to {@code content}; the returned content may
     * still violate rules we can't fix mechanically (the caller re-checks and reports the rest).
     */
    static String apply(String content, List<Violation> violations) {
        List<String> lines = new ArrayList<>(content.lines().toList());
        boolean endsWithNewline = content.endsWith("\n");

        // Import-block problems are fixed holistically once, not per-violation.
        boolean redoImports = violations.stream()
            .anyMatch(v -> v.rule().equals("CustomImportOrder") || v.rule().equals("RedundantImport"));

        List<Violation> ordered = violations.stream()
            .sorted(Comparator.comparingInt(Violation::line).thenComparingInt(Violation::col).reversed())
            .toList();

        for (Violation v : ordered) {
            int i = v.line() - 1;
            if (i < 0 || i >= lines.size())
                continue;

            switch (v.rule()) {
                case "UnusedImports" -> lines.set(i, null);

                case "FileTabCharacter" -> lines.set(i, lines.get(i).replace("\t", "    "));

                case "WhitespaceAfter" -> insertAt(lines, i, v.col(), " ");

                case "NoWhitespaceBefore" -> stripSpacesBefore(lines, i, v.col());

                case "NoWhitespaceAfter" -> stripSpacesAfter(lines, i, v.col());

                case "MethodParamPad" -> stripSpacesBefore(lines, i, v.col());

                case "SingleSpaceSeparator" -> collapseSpacesAt(lines, i, v.col());

                case "WhitespaceAround" -> whitespaceAround(lines, i, v);

                case "ModifierOrder" -> reorderModifiers(lines, i);

                case "EmptyLineSeparator" -> emptyLineSeparator(lines, i, v);

                case "AnnotationOnSameLine" -> joinAnnotationLine(lines, i);

                default -> {
                    // not mechanically fixable here — reported back as remaining
                }
            }
        }

        lines.removeIf(java.util.Objects::isNull);

        if (redoImports)
            reorderImports(lines);

        String out = String.join("\n", lines);
        if (endsWithNewline || violations.stream().anyMatch(v -> v.rule().equals("NewlineAtEndOfFile")))
            out = out + "\n";

        return out;
    }

    private static void insertAt(List<String> lines, int i, int col, String s) {
        String l = lines.get(i);
        if (col > 0 && col <= l.length())
            lines.set(i, l.substring(0, col) + s + l.substring(col));
    }

    private static void stripSpacesBefore(List<String> lines, int i, int col) {
        String l = lines.get(i);
        int at = Math.min(col - 1, l.length());
        int start = at;
        while (start > 0 && l.charAt(start - 1) == ' ')
            start--;
        if (start < at)
            lines.set(i, l.substring(0, start) + l.substring(at));
    }

    private static void stripSpacesAfter(List<String> lines, int i, int col) {
        String l = lines.get(i);
        int at = Math.min(col, l.length());
        int end = at;
        while (end < l.length() && l.charAt(end) == ' ')
            end++;
        if (end > at)
            lines.set(i, l.substring(0, at) + l.substring(end));
    }

    private static void collapseSpacesAt(List<String> lines, int i, int col) {
        String l = lines.get(i);
        int at = Math.min(col - 1, l.length() - 1);
        int start = at;
        while (start > 0 && l.charAt(start - 1) == ' ')
            start--;
        int end = at;
        while (end < l.length() && l.charAt(end) == ' ')
            end++;
        if (end - start > 1)
            lines.set(i, l.substring(0, start) + " " + l.substring(end));
    }

    private static void whitespaceAround(List<String> lines, int i, Violation v) {
        boolean before = v.message().contains("not preceded");
        String l = lines.get(i);
        int at = v.col() - 1;
        if (at < 0 || at > l.length())
            return;
        if (before)
            lines.set(i, l.substring(0, at) + " " + l.substring(at));
        else {
            // "'x' is not followed by whitespace": the token starts at col; find its end and pad
            Matcher m = Pattern.compile("\\S+").matcher(l);
            if (m.find(at))
                lines.set(i, l.substring(0, m.end()) + " " + l.substring(m.end()));
        }
    }

    private static void reorderModifiers(List<String> lines, int i) {
        String l = lines.get(i);
        Matcher m = Pattern.compile("^(\\s*)((?:(?:public|protected|private|abstract|default|static|final|transient|volatile|synchronized|native|strictfp)\\s+)+)").matcher(l);
        if (!m.find())
            return;

        List<String> mods = new ArrayList<>(List.of(m.group(2).trim().split("\\s+")));
        mods.sort(Comparator.comparingInt(MODIFIER_ORDER::indexOf));
        lines.set(i, m.group(1) + String.join(" ", mods) + " " + l.substring(m.end()));
    }

    private static void emptyLineSeparator(List<String> lines, int i, Violation v) {
        if (v.message().contains("more than 1 empty line")) {
            int j = i - 1;
            while (j > 0 && lines.get(j) != null && lines.get(j).isBlank()
                && lines.get(j - 1) != null && lines.get(j - 1).isBlank())
                lines.set(j--, null);
        }
        else if (v.message().contains("should be separated"))
            lines.add(i, "");
    }

    private static void joinAnnotationLine(List<String> lines, int i) {
        String l = lines.get(i);
        if (l != null && l.strip().startsWith("@") && i + 1 < lines.size() && lines.get(i + 1) != null) {
            lines.set(i, l.stripTrailing() + " " + lines.get(i + 1).stripLeading());
            lines.remove(i + 1);
        }
    }

    /**
     * Rewrites the import block per the repo's CustomImportOrder: java.* first, then javax/jakarta,
     * then everything else, statics last; alphabetical within each group, one solid block.
     */
    private static void reorderImports(List<String> lines) {
        int first = -1;
        int last = -1;
        List<String> imports = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l != null && l.startsWith("import ")) {
                if (first < 0)
                    first = i;
                last = i;
                if (!imports.contains(l.strip()))
                    imports.add(l.strip());
            }
            else if (first >= 0 && l != null && !l.isBlank())
                break; // past the import block
        }
        if (first < 0)
            return;

        Comparator<String> alpha = Comparator.comparing(s -> s.replaceAll("^import\\s+(static\\s+)?", ""));
        List<String> sorted = new ArrayList<>();
        imports.stream().filter(s -> !s.startsWith("import static") && s.startsWith("import java."))
            .sorted(alpha).forEach(sorted::add);
        imports.stream().filter(s -> !s.startsWith("import static")
                && (s.startsWith("import javax.") || s.startsWith("import jakarta.")))
            .sorted(alpha).forEach(sorted::add);
        imports.stream().filter(s -> !s.startsWith("import static") && !s.startsWith("import java.")
                && !s.startsWith("import javax.") && !s.startsWith("import jakarta."))
            .sorted(alpha).forEach(sorted::add);
        imports.stream().filter(s -> s.startsWith("import static")).sorted(alpha).forEach(sorted::add);

        // Replace the whole [first..last] stretch (imports and any stray blanks inside) with the block.
        for (int i = last; i >= first; i--)
            lines.remove(i);
        lines.addAll(first, sorted);
    }
}
