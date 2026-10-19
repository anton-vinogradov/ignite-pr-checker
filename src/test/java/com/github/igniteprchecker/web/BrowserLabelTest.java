package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ⚙ lists a login by the browser and the system it came from, so that alice tells her laptop from a colleague's
 * computer. Every User-Agent of Edge and Opera also says Chrome and Safari, and every one of Android also says Linux:
 * a check in the wrong order names them all Chrome on Linux.
 */
class BrowserLabelTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 "
            + "Safari/537.36 | Chrome on macOS",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 "
            + "Safari/537.36 Edg/141.0.0.0 | Edge on Windows",
        "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0 | Firefox on Linux",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/18.6 Mobile/15E148 Safari/604.1 | Safari on iPhone",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile "
            + "Safari/537.36 | Chrome on Android",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 "
            + "Safari/537.36 OPR/123.0.0.0 | Opera on Windows",
        "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 "
            + "Safari/537.36 | Chrome on ChromeOS",
        "Mozilla/5.0 (X11; Linux x86_64) | a browser on Linux",
        "curl/8.4.0 | curl",
        "python-requests/2.32.3 | python-requests",
        "' ' | unknown browser",
        "'<script>' | unknown browser"})
    void namesTheBrowserAndItsSystem(String userAgent, String label) {
        assertThat(BrowserLabel.of(userAgent)).isEqualTo(label);
    }

    @Test
    void aRequestWithoutAUserAgentIsAnUnknownBrowser() {
        assertThat(BrowserLabel.of(null)).isEqualTo(BrowserLabel.UNKNOWN);
    }
}
