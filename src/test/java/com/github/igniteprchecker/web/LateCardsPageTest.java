package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * On PR 13592 "This PR's tests" came 5.5 s after the verdict with about 30 classes, above "Crashed near the end of the
 * run" and "Not blamed on this PR": the reader's open Not blamed jumped down under the list. The two cards now wait
 * for the PR's tests, or for 20 s when no answer comes, and keep their place.
 */
class LateCardsPageTest {
    /** PR 13592's verdict with a suite that crashed near the end; the PR's tests answer when the scenario says. */
    private static final String SETUP = PageScript.SIGNED_IN + """
        page.route('/api/analyze', { body: verdict({ prNumber: 13575, unstableSuites: [{ suite: 'IgniteTests24Java8_Snapshots6',
            suiteName: 'Snapshots 6', suiteBuildId: 9003, problems: ['execution timeout'] }] }) });
        let answer = null;
        const prTests = { buildId: 9001, note: null, classes: [{ name: 'org.apache.ignite.AbstractRollingUpgradeMessageTest',
            path: 'p1', added: true, runs: [] }] };
        page.route('/api/pr-tests', () => new Promise(resolve => { answer = resolve; }));
        const shown = id => !page.el(id).classList.contains('hidden');
        const cards = () => ({ prTests: shown('prTestsCard'), unstable: shown('unstableCard'), filtered: shown('filteredCard') });
        """;

    @Test
    void theCardsUnderThePrsTestsWaitForItsAnswer() throws Exception {
        JsonNode out = PageScript.run("index.html", SETUP + """
            await page.load('?pr=13575');
            const before = cards();
            answer({ body: prTests });
            await page.settle();
            report({ before, after: cards() });
            """);

        assertThat(out.get("before").toString()).isEqualTo("{\"prTests\":false,\"unstable\":false,\"filtered\":false}");
        assertThat(out.get("after").toString()).isEqualTo("{\"prTests\":true,\"unstable\":true,\"filtered\":true}");
    }

    /** When the server fails, no card comes to push them down: they show at once. */
    @Test
    void aFailedAnswerShowsThemAtOnce() throws Exception {
        JsonNode out = PageScript.run("index.html", SETUP + """
            page.route('/api/pr-tests', { status: 502, body: {} });
            await page.load('?pr=13575');
            report(cards());
            """);

        assertThat(out.toString()).isEqualTo("{\"prTests\":false,\"unstable\":true,\"filtered\":true}");
    }

    @Test
    void withoutAnAnswerTheyShowAfterTheWait() throws Exception {
        JsonNode out = PageScript.run("index.html", SETUP + """
            await page.load('?pr=13575');
            await page.tick(page.run('LATE_CARDS_WAIT_MS') - 1000);
            const waiting = cards();
            await page.tick(1000);
            report({ waiting, after: cards() });
            """);

        assertThat(out.get("waiting").get("filtered").asBoolean()).isFalse();
        assertThat(out.get("after").toString()).isEqualTo("{\"prTests\":false,\"unstable\":true,\"filtered\":true}");
    }

    /** A newer run of the same PR keeps the card in place while its tests are asked for again: nothing hides. */
    @Test
    void aNewerRunOfTheSamePrKeepsThemShown() throws Exception {
        JsonNode out = PageScript.run("index.html", SETUP + """
            await page.load('?pr=13575');
            answer({ body: prTests });
            await page.settle();
            page.route('/api/analyze', { body: verdict({ buildId: 9002 }) });
            await page.run('analyze')(13575, true);
            await page.settle();
            report({ asked: page.fetches('/api/pr-tests').length, cards: cards() });
            """);

        assertThat(out.get("asked").asInt()).isEqualTo(2);
        assertThat(out.get("cards").toString()).isEqualTo("{\"prTests\":true,\"unstable\":false,\"filtered\":true}");
    }

    /** Another PR's card is not this one's: its cards wait for its own answer. */
    @Test
    void anotherPrWaitsForItsOwnAnswer() throws Exception {
        JsonNode out = PageScript.run("index.html", SETUP + """
            await page.load('?pr=13575');
            answer({ body: prTests });
            await page.settle();
            page.route('/api/analyze', { body: verdict({ prNumber: 13461 }) });
            page.run('openPr')(13461, true);
            await page.settle();
            const before = cards();
            answer({ body: { buildId: 9001, note: null, classes: [] } });
            await page.settle();
            report({ before, after: cards() });
            """);

        assertThat(out.get("before").toString()).isEqualTo("{\"prTests\":false,\"unstable\":false,\"filtered\":false}");
        assertThat(out.get("after").toString()).isEqualTo("{\"prTests\":false,\"unstable\":false,\"filtered\":true}");
    }

    @Test
    void theToursSayHowLongTheyWait() throws Exception {
        Matcher wait = Pattern.compile("const LATE_CARDS_WAIT_MS = (\\d+);")
            .matcher(Files.readString(Path.of("src/main/resources/static/index.js"), UTF_8));
        assertThat(wait.find()).isTrue();
        long seconds = Long.parseLong(wait.group(1)) / 1000;

        assertThat(Files.readString(Path.of("docs/features.md"), UTF_8)).contains("they show after " + seconds + " s.");
        assertThat(Files.readString(Path.of("docs/features.ru.md"), UTF_8).replaceAll("\\s+", " "))
            .contains("они появляются через " + seconds + " с.");
    }
}
