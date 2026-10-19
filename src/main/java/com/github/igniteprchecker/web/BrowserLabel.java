package com.github.igniteprchecker.web;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names the browser and the system of a login from its User-Agent, such as "Firefox on Windows", so that the user can
 * tell their logins apart in ⚙. No version: it changes with every browser update and helps no one pick a login. A
 * client that is no browser, such as curl, is named by the first word of its User-Agent.
 */
final class BrowserLabel {
    /** What a login is called when its User-Agent names nothing. */
    static final String UNKNOWN = "unknown browser";

    /** Each browser by a mark its User-Agent has, in the order to look: Edge and Opera also say Chrome and Safari. */
    private static final List<Map.Entry<String, String>> BROWSERS = List.of(
        Map.entry("Edg", "Edge"),
        Map.entry("OPR/", "Opera"),
        Map.entry("YaBrowser/", "Yandex Browser"),
        Map.entry("Vivaldi/", "Vivaldi"),
        Map.entry("SamsungBrowser/", "Samsung Internet"),
        Map.entry("Firefox/", "Firefox"),
        Map.entry("FxiOS/", "Firefox"),
        Map.entry("CriOS/", "Chrome"),
        Map.entry("Chrome/", "Chrome"),
        Map.entry("Chromium/", "Chromium"),
        Map.entry("Safari/", "Safari"));

    /** Each system by a mark its User-Agent has, in the order to look: Android and ChromeOS also say Linux. */
    private static final List<Map.Entry<String, String>> SYSTEMS = List.of(
        Map.entry("Windows", "Windows"),
        Map.entry("iPhone", "iPhone"),
        Map.entry("iPad", "iPad"),
        Map.entry("Android", "Android"),
        Map.entry("CrOS", "ChromeOS"),
        Map.entry("Mac OS X", "macOS"),
        Map.entry("Macintosh", "macOS"),
        Map.entry("Linux", "Linux"));

    /** The first word of a User-Agent that names no browser, as the label shows it. */
    private static final Pattern PRODUCT = Pattern.compile("^([A-Za-z][A-Za-z0-9._-]{0,31})");

    private BrowserLabel() {
    }

    /** The label of a login made with this User-Agent; {@link #UNKNOWN} for none. */
    static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank())
            return UNKNOWN;

        String browser = first(BROWSERS, userAgent);
        String system = first(SYSTEMS, userAgent);
        if (browser != null)
            return system == null ? browser : browser + " on " + system;
        if (userAgent.startsWith("Mozilla/"))
            return system == null ? UNKNOWN : "a browser on " + system;

        Matcher product = PRODUCT.matcher(userAgent);

        return product.find() ? product.group(1) : UNKNOWN;
    }

    private static String first(List<Map.Entry<String, String>> marks, String userAgent) {
        for (Map.Entry<String, String> m : marks) {
            if (userAgent.contains(m.getKey()))
                return m.getValue();
        }

        return null;
    }
}
