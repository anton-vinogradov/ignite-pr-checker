package com.github.igniteprchecker.suitefix;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Picks the suite that should run a test class no suite runs, from the suites of the class's module. */
interface SuiteChooser {
    /** Whether a suite can be picked at all: without the operator's Anthropic API key it can't. */
    boolean available();

    /**
     * The suite among {@code suites} for the class at {@code path}, whose source is {@code source}.
     *
     * @throws NoChoice with what went wrong, in words for the PR's author
     */
    Choice choose(String path, String source, List<TestSuites.Suite> suites);

    /** The pick: a suite's path as listed, a class it lists to put the new one next to, and why this suite. */
    record Choice(
        @JsonPropertyDescription("The path of the chosen suite, exactly as listed") String suite,
        @JsonPropertyDescription("A class listed under that suite, the closest in subject to the new class; the new "
            + "class goes next to it") String neighbour,
        @JsonPropertyDescription("Why this suite, in one plain sentence of at most 25 words") String reason) {
    }

    /** No suite was picked, with why. */
    final class NoChoice extends RuntimeException {
        NoChoice(String why) {
            super(why);
        }
    }
}
