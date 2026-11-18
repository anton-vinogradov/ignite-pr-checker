package com.github.igniteprchecker.jira;

/** The markup a verdict is written in: GitHub's markdown for the PR comment, JIRA's wiki markup for the visa. */
enum Ink {
    MARKDOWN, WIKI;

    String bold(String s) {
        return this == MARKDOWN ? "**" + s + "**" : "*" + s + "*";
    }

    /** Text from TeamCity or GitHub, written so that it reads as written. */
    String text(String s) {
        return this == MARKDOWN ? Markup.text(s) : Markup.wikiText(s);
    }

    String code(String s) {
        return this == MARKDOWN ? Markup.code(s) : "{{" + Markup.wikiText(s) + "}}";
    }

    String link(String text, String url) {
        return this == MARKDOWN ? "[" + text + "](" + url + ")" : "[" + text + "|" + url + "]";
    }

    /** The mark a line opens with: an emoji in GitHub, one of JIRA's icons in a visa. */
    String icon(String markdown, String wiki) {
        return this == MARKDOWN ? markdown : wiki;
    }
}
