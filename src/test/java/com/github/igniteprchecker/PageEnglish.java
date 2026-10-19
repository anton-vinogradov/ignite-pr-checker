package com.github.igniteprchecker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A page script as English readers see it: each string the script puts into the page's language, {@code
 * ${T('Rerun')}} or {@code ${T`Rerun ${n} suites`}} in a template, stands there as its English, so a check of the
 * script's words reads them in place.
 */
public final class PageEnglish {
    private static final Pattern CALL = Pattern.compile("\\$\\{T\\('((?:[^'\\\\]|\\\\.)*)'\\)}");

    private static final Pattern TAG = Pattern.compile("\\$\\{T`([^`]*)`}");

    private PageEnglish() {
    }

    /** {@code js} with its translated strings written out. */
    public static String of(String js) {
        Matcher call = CALL.matcher(js);
        StringBuilder out = new StringBuilder();
        while (call.find())
            call.appendReplacement(out, Matcher.quoteReplacement(call.group(1).replaceAll("\\\\(.)", "$1")));
        call.appendTail(out);

        return TAG.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
    }
}
