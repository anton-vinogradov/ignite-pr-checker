package com.github.igniteprchecker.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * What a page in Russian still says in English. The Russian page keeps the terms the Russian tour keeps in English
 * ({@link #TERMS}: RunAll, master and TeamCity are single words and pass anyway) and the names a scenario gives its
 * suites, tests, PRs and browsers; any other two English words in a row are a phrase the catalog missed.
 */
final class EnglishLeft {
    /** Two or more English words in a row. A name joined by "-" or "/" is one word. */
    static final Pattern PHRASE =
        Pattern.compile("(?<![\\w/.-])[A-Za-z][a-z'./-]*[a-z](?:,?\\s+[A-Za-z][a-z'./-]*[a-z])+(?![\\w/-])");

    /** The terms of several words that docs/features.ru.md keeps in English, as a Russian page shows them. */
    static final List<String> TERMS = List.of("Ignite PR Checker", "GitHub API", "Firefox on Linux");

    private static final Pattern TITLE = Pattern.compile("title=\"([^\"]*)\"");

    private EnglishLeft() {
    }

    /** The English phrases of {@code texts} and of the tooltips in {@code html}, the terms and {@code names} aside. */
    static List<String> in(JsonNode texts, String html, List<String> names) {
        return in(texts, html, names, Pattern.compile("(?!)"));
    }

    /**
     * The English phrases of {@code texts} (each named by its key) and of the tooltips in {@code html}, with the
     * terms, the {@code names} of the scenario and what {@code foreign} matches cut out first: texts the page shows in
     * the words of another, such as TeamCity's messages or the service's log.
     */
    static List<String> in(JsonNode texts, String html, List<String> names, Pattern foreign) {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : texts.properties())
            all.add(e.getKey() + ": " + e.getValue().asText());
        Matcher title = TITLE.matcher(html);
        while (title.find())
            all.add("title: " + title.group(1));

        List<String> left = new ArrayList<>();
        for (String line : all) {
            String text = foreign.matcher(line.substring(line.indexOf(": ") + 2)).replaceAll("")
                .replaceAll("https?://\\S+", "");
            for (String name : TERMS)
                text = text.replace(name, "");
            for (String name : names)
                text = text.replace(name, "");
            Matcher phrase = PHRASE.matcher(text);
            while (phrase.find())
                left.add(line.substring(0, line.indexOf(':')) + ": «" + phrase.group() + "» in " + line);
        }

        return left;
    }
}
