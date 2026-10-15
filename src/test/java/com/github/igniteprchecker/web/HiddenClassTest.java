package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The status page put class "hidden" on its "UI updated — reload" pill but never defined the class, so the pill
 * showed on every visit from the day it was added. A page that hides by that class must define it, in its own style
 * or in a stylesheet it links.
 */
class HiddenClassTest {
    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Pattern USES = Pattern.compile("class=\"[^\"]*\\bhidden\\b|classList\\.(add|remove|toggle)\\('hidden'");

    private static final Pattern DEFINES = Pattern.compile("(?m)^\\s*\\.hidden\\s*\\{[^}]*display:\\s*none");

    private static final Pattern LINKED = Pattern.compile("<link rel=\"stylesheet\" href=\"/([^\"]+)\">");

    @ParameterizedTest
    @ValueSource(strings = {"index", "flaky", "status"})
    void aPageThatHidesByTheClassDefinesIt(String page) throws Exception {
        String html = Files.readString(STATIC.resolve(page + ".html"));
        String js = Files.readString(STATIC.resolve(page + ".js")) + Files.readString(STATIC.resolve("common.js"));

        StringBuilder css = new StringBuilder(html);
        Matcher linked = LINKED.matcher(html);
        while (linked.find())
            css.append(Files.readString(STATIC.resolve(linked.group(1))));
        if (USES.matcher(html).find() || USES.matcher(js).find())
            assertThat(DEFINES.matcher(css).find()).as(page + ".html defines .hidden").isTrue();
    }
}
