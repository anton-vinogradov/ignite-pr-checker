package com.github.igniteprchecker.jira;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text from TeamCity or a PR comment, such as a test's name, why a suite broke, a ci2 user's name or a word of a
 * command, written into a PR comment in GitHub's markdown or into a visa in JIRA's wiki markup so that it reads as
 * written. A PR's own code names its tests and can print what TeamCity reports as a build problem, whoever registers on
 * ci2 picks their own name, and whoever posts a command types any word in it. Written as is, a name with two backticks
 * in a row ends its code span, and the rest of it makes links, mentions and HTML in a comment the checker's account
 * posts.
 */
public final class Markup {
    /** A line break or another control character: after a line break, a list item can go on with a heading. */
    private static final Pattern BREAK = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

    /**
     * What opens code, a link, an image, emphasis, HTML or an entity in GitHub's markdown, the escape character
     * itself, and what starts a heading, a quote or a list at the start of a list item. An underscore inside a word
     * opens nothing there.
     */
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\`*\\[\\]<>!&~]|(?<!\\p{Alnum})_|_(?!\\p{Alnum})"
        + "|^[#+-]|(?<=^\\d{1,9})[.)]");

    /**
     * What GitHub or JIRA makes a link of by itself: a mention, an e-mail address, an issue number ({@code #123} or
     * {@code GH-123}), a web address.
     */
    private static final Pattern LINKED = Pattern.compile("@(?=\\p{Alnum})|#(?=\\d)|(?i:\\bgh-)(?=\\d)|:(?=//)"
        + "|(?i:www)(?=\\.)");

    /** Put after what {@link #LINKED} finds, it keeps GitHub and JIRA from making the link, and shows nothing. */
    private static final String WORD_JOINER = "\u2060";

    /** What JIRA reads as a link, a mention, a macro, an image or an escape. */
    private static final Pattern WIKI = Pattern.compile("[\\[\\]{}\\\\!]");

    private Markup() {
    }

    /** Plain text in GitHub's markdown. */
    public static String text(String s) {
        return MARKDOWN.matcher(unlinked(oneLine(s).strip()))
            .replaceAll(m -> Matcher.quoteReplacement("\\" + m.group()));
    }

    /**
     * A code span in GitHub's markdown that holds {@code s} whole: its fence is one backtick longer than any run of
     * backticks in it.
     */
    public static String code(String s) {
        String line = oneLine(s);
        int longest = 0;
        for (int i = 0, run = 0; i < line.length(); i++) {
            run = line.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);

        return longest == 0 ? fence + line + fence : fence + " " + line + " " + fence;
    }

    /** Text in JIRA's wiki markup, plain or inside {@code {{…}}}. */
    static String wikiText(String s) {
        return WIKI.matcher(unlinked(oneLine(s))).replaceAll(m -> Matcher.quoteReplacement("\\" + m.group()));
    }

    private static String unlinked(String s) {
        return LINKED.matcher(s).replaceAll(m -> m.group() + WORD_JOINER);
    }

    private static String oneLine(String s) {
        return BREAK.matcher(String.valueOf(s)).replaceAll(" ");
    }
}
