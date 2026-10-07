package com.github.igniteprchecker.analysis;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a failed test's output as TeamCity stores it: the message, its stack trace, then the test's whole
 * stdout and stderr, often hundreds of kilobytes. Only the message and the stack trace say what failed: the
 * stdout of every Ignite test logs "SYSTEM_CRITICAL_OPERATION_TIMEOUT" at each node start, which once made
 * plain assertion failures read as timing noise.
 */
public final class FailureOutput {
    /** The most of a failure's output that is kept, about what a chat with an AI assistant takes in. */
    static final int MAX_CHARS = 32_000;

    private static final Pattern SEPARATOR =
        Pattern.compile("^-{3,}[ \\t]*Std(?:out|err):?[ \\t]*-{3,}[ \\t]*\\r?$", Pattern.MULTILINE);

    private static final Pattern HANG = Pattern.compile("(?i)Test has been timed out|test timed out after");

    private static final Pattern ASSERTION = Pattern.compile(
        "Assertion(?:Error|FailedError|Exception)|ComparisonFailure|expected:<|\\bExpected: |\\bcheck .+ has failed");

    private static final Pattern ENVIRONMENT = Pattern.compile("(?i)timed out|timeout|Node has not joined"
        + "|OutOfMemoryError|JVM crash|Connection re(?:set|fused)|Failed to wait|exit code|Agent unavailable");

    private static final Pattern TEST_THREAD =
        Pattern.compile("^Thread \\[name=\"test-runner-[^\\n]*\\n(?:[ \\t]+[^\\n]*\\n?)*", Pattern.MULTILINE);

    private static final String TEST_THREAD_NOTE = "\n[the test's thread in the thread dump:]\n";

    private static final Pattern EXCEPTION_CLASS = Pattern.compile("(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*:");

    private FailureOutput() {
    }

    /** What a failure looks like from its message and stack trace; the page words the advice. */
    public enum Kind {
        /** The test ran out of its time: the thread dump shows where it waits. */
        HANG,

        /** A failed check: most likely a real logic failure. */
        ASSERTION,

        /** Environment or timing trouble, which a re-run may not meet again. */
        ENVIRONMENT;

        /** The name the page knows it by. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The message and its stack trace: the output up to where the test's own stdout starts. Java puts the
     * stack after a first "------- Stdout: -------" line, starting with the message again, and the stdout
     * after a second one; .NET puts the stack right under the message and the stdout after the first.
     */
    public static String head(String details) {
        if (details == null)
            return "";

        Matcher sep = SEPARATOR.matcher(details);
        if (!sep.find())
            return details;

        int first = sep.start();
        int afterFirst = sep.end();
        int second = sep.find() ? sep.start() : details.length();
        String message = details.substring(0, first);

        return startsWithException(details.substring(afterFirst, second), message)
            ? details.substring(0, second) : message;
    }

    /** The kind of failure its message and stack trace show, or null when they show none of them. */
    public static Kind kind(String details) {
        String head = head(details);

        if (HANG.matcher(head).find())
            return Kind.HANG;

        if (ASSERTION.matcher(head).find())
            return Kind.ASSERTION;

        if (ENVIRONMENT.matcher(head).find())
            return Kind.ENVIRONMENT;

        return null;
    }

    /**
     * The output cut to {@link #MAX_CHARS}: the message and its stack trace, for a hang the test's thread
     * from the thread dump, then the stdout from its start, and a note saying it was cut. A 470 KB thread
     * dump does not fit a chat with an AI assistant, and the assistant cannot open TeamCity to read the rest.
     */
    public static String trim(String details) {
        if (details == null || details.length() <= MAX_CHARS)
            return details;

        String head = head(details);
        String rest = details.substring(head.length());
        StringBuilder out = new StringBuilder(upToLine(head, MAX_CHARS));

        if (kind(details) == Kind.HANG) {
            Matcher thread = TEST_THREAD.matcher(rest);
            int room = MAX_CHARS - out.length() - TEST_THREAD_NOTE.length();
            if (thread.find() && room > 0)
                out.append(TEST_THREAD_NOTE).append(upToLine(thread.group(), room));
        }

        if (out.length() < MAX_CHARS)
            out.append(upToLine(rest, MAX_CHARS - out.length()));

        return out.append(String.format(Locale.ROOT,
            "\n[truncated: %,d of %,d characters kept; the whole output is in TeamCity]", out.length(),
            details.length())).toString();
    }

    /**
     * Whether the section is a stack trace of this message: Java's starts with the exception and its message.
     * A message on lines of its own (hamcrest's "\nExpected: …") leaves the exception's class alone on the first.
     */
    private static boolean startsWithException(String section, String message) {
        String expected = abbreviated(firstLine(message));
        List<String> lines = section.lines().map(String::strip).filter(l -> !l.isEmpty()).limit(2).toList();
        if (lines.isEmpty())
            return expected.isEmpty();

        return lines.get(0).contains(expected)
            || lines.size() > 1 && EXCEPTION_CLASS.matcher(lines.get(0)).matches() && lines.get(1).contains(expected);
    }

    private static String firstLine(String s) {
        return s.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
    }

    private static String abbreviated(String line) {
        return line.length() > 60 ? line.substring(0, 60) : line;
    }

    /** At most {@code max} characters of {@code s}, ending at a line end when one is in reach. */
    private static String upToLine(String s, int max) {
        if (s.length() <= max)
            return s;

        int end = s.lastIndexOf('\n', max);

        return s.substring(0, end > 0 ? end + 1 : max);
    }
}
