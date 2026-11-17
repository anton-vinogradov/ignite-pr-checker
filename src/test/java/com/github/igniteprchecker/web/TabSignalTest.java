package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * From the end of a chain to the final verdict took 133 minutes on average, and nothing told the author it was
 * there: the tab kept its title and icon, the page could not notify, and a hidden tab stopped asking. The tab now
 * says how the run stands, a hidden one keeps asking until the verdict is final, and "Notify me" raises one
 * notification once it is.
 */
class TabSignalTest {
    /** RunAll 9100 of PR 13575 running with 65 minutes left; the verdict on screen is of the run before, 9001. */
    private static final String RUNNING = """
        const CHAIN = { buildId: 9100, runAll: true, state: 'running', name: 'RunAll', mine: true, by: 'alice',
            webUrl: 'https://ci2.example/build/9100', leftSec: 3900, pct: 40 };
        page.route('/api/runs', { body: [CHAIN] });
        page.route('/api/settling', { body: { phase: 'running', wave: 0, of: 2 } });
        """;

    /** RunAll 9100 has finished: its verdict comes from the refresh, and its re-runs settle it. */
    private static final String FINISHED = """
        page.route('/api/runs', { body: [] });
        page.route('/api/refresh', { body: verdict({ buildId: 9100 }) }, 'POST');
        page.route('/api/analyze', { body: verdict({ buildId: 9100 }) });
        """;

    private static final String ICON = "page.el('query:link[rel=\"icon\"]').href";

    /** A desktop that grants notifications and records the ones raised in {@code shown}. */
    private static final String NOTIFICATIONS = """
        const shown = [];
        page.run('window').Notification = class {
            constructor(title, opts) { shown.push({ title, body: opts.body }); }
            close() {}
        };
        page.run('window').Notification.permission = 'granted';
        page.run('window').Notification.requestPermission = async () => 'granted';
        """;

    /** /api/settling of RunAll 9100: its first wave of re-runs goes. */
    private static final String SETTLING = "{ phase: 'settling', wave: 1, of: 2, what: '1 suite with blockers' }";

    /** A route that holds /api/{@code path} back until {@code release()} is called. */
    private static final String HELD = """
        const win = page.run('window');
        const passOn = win.fetch;
        let release = null;
        win.fetch = (url, opts) => url.startsWith('/api/%s') && !release
            ? new Promise(r => { release = () => r(passOn(url, opts)); }) : passOn(url, opts);
        """;

    @Test
    void theTabSaysHowTheRunStands() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            const running = { title: page.run('document').title, icon: %1$s };
            page.route('/api/runs', { body: [] });
            page.route('/api/settling', { body: { phase: 'settling', wave: 2, of: 2, what: '3 suites with blockers',
                etaEpochSec: Math.floor(page.now() / 1000) + 25 * 60 } });
            await page.tick(15000);
            const settling = { title: page.run('document').title, icon: %1$s };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(90000);
            const final = { title: page.run('document').title, icon: %1$s };
            page.run('goHome')(true);
            report({ running, settling, final, home: { title: page.run('document').title, icon: %1$s } });
            """.formatted(ICON));

        assertThat(out.get("running").get("title").asText()).isEqualTo("⏱ ~1h 05m left · #13575 — Ignite PR Checker");
        assertThat(out.get("running").get("icon").asText()).startsWith("data:image/svg+xml,").contains("%231cb6ed");
        assertThat(out.get("settling").get("title").asText()).isEqualTo("♻️ re-run 2/2 ~25m · #13575 — Ignite PR Checker");
        assertThat(out.get("settling").get("icon").asText()).contains("%23e3a008");
        assertThat(out.get("final").get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
        assertThat(out.get("final").get("icon").asText()).contains("%23d73a49");
        assertThat(out.get("home").get("title").asText()).isEqualTo("Ignite PR Checker");
        assertThat(out.get("home").get("icon").asText()).isEqualTo("/favicon.png");
    }

    /**
     * Before the first wave of re-runs, the line at the top of the page says "Deciding whether to re-run the failed
     * suites", and the tab said "settling", a word the page uses nowhere else.
     */
    @Test
    void theTabSaysDecidingBeforeTheFirstWave() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            page.route('/api/runs', { body: [] });
            page.route('/api/settling', { body: { phase: 'settling', wave: 0, of: 2 } });
            await page.tick(15000);
            report({ title: page.run('document').title, line: page.el('settling').textContent,
                steps: page.el('steps').innerHTML });
            """);

        assertThat(out.get("title").asText()).isEqualTo("♻️ deciding on re-run · #13575 — Ignite PR Checker");
        assertThat(out.get("line").asText()).startsWith("Preliminary verdict — wait");
        assertThat(out.get("steps").asText())
            .contains("<span class=\"step-name\">Auto re-run</span><span class=\"step-detail\">deciding</span>");
    }

    @Test
    void aHiddenTabKeepsAskingUntilTheVerdictIsFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            await page.setHidden(true);
            const before = page.fetches('/api/runs').length;
            await page.tick(90000);
            const whileRunning = page.fetches('/api/runs').length - before;
            page.route('/api/runs', { body: [] });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(200000);
            const atFinal = page.fetches('/api/runs').length;
            await page.tick(600000);
            report({ whileRunning, afterFinal: page.fetches('/api/runs').length - atFinal,
                title: page.run('document').title });
            """);

        assertThat(out.get("whileRunning").asInt()).isEqualTo(1);
        assertThat(out.get("afterFinal").asInt()).isZero();
        assertThat(out.get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
    }

    @Test
    void notifyMeFiresOnceWhenTheVerdictOfTheRunIsFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            const shown = [];
            page.run('window').Notification = class {
                constructor(title, opts) { shown.push({ title, body: opts.body }); }
                close() {}
            };
            page.run('window').Notification.permission = 'default';
            page.run('window').Notification.requestPermission = async () => {
                page.run('window').Notification.permission = 'granted';
                return 'granted';
            };
            await page.load('?pr=13575');
            const offered = !page.el('notifyBtn').classList.contains('hidden');
            await page.el('notifyBtn').onclick();
            await page.settle();
            const armed = page.el('notifyBtn').textContent;
            %s
            page.route('/api/settling', url => ({ body: url.includes('build=9100')
                ? { phase: 'settling', wave: 1, of: 2, what: '1 suite with blockers' } : { phase: 'final', wave: 0, of: 2 } }));
            await page.tick(15000);
            const whileSettling = shown.length;
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(90000);
            await page.tick(90000);
            report({ offered, armed, whileSettling, shown, hidden: page.el('notifyBtn').classList.contains('hidden') });
            """.formatted(FINISHED));

        assertThat(out.get("offered").asBoolean()).isTrue();
        assertThat(out.get("armed").asText()).isEqualTo("🔔 Will notify");
        assertThat(out.get("whileSettling").asInt()).isZero();
        assertThat(out.get("shown")).hasSize(1);
        assertThat(out.get("shown").get(0).get("title").asText()).isEqualTo("PR #13575: 1 blocker");
        assertThat(out.get("shown").get(0).get("body").asText()).isEqualTo("The verdict of RunAll 9100 is final.");
        assertThat(out.get("hidden").asBoolean()).isTrue();
    }

    @Test
    void aBrowserThatBlocksNotificationsIsNotOfferedTheButton() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            page.run('window').Notification = class {};
            page.run('window').Notification.permission = 'denied';
            await page.load('?pr=13575');
            report({ hidden: page.el('notifyBtn').classList.contains('hidden') });
            """);

        assertThat(out.get("hidden").asBoolean()).isTrue();
    }

    /** Given a failed status read, the page admits it is unknown and keeps asking until it confirms a final result. */
    @Test
    void aFailedAnswerKeepsPollingWithoutClaimingTheRunIsFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + NOTIFICATIONS + """
            page.route('/api/analyze', { body: verdict({ buildId: 9100 }) });
            page.route('/api/settling', { body: %1$s });
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            await page.setHidden(true);
            page.route('/api/settling', { status: 502, body: {} });
            await page.tick(90000);
            const atBlip = { title: page.run('document').title, shown: shown.length,
                banner: !page.el('settlingRow').classList.contains('hidden'), text: page.el('settling').textContent };
            page.route('/api/settling', { body: %1$s });
            await page.tick(90000);
            const asked = page.fetches('/api/runs').length;
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(3600000);
            report({ atBlip, askedAfter: page.fetches('/api/runs').length - asked, title: page.run('document').title,
                shown });
            """.formatted(SETTLING));

        assertThat(out.get("atBlip").get("title").asText()).isEqualTo("#13575 — Ignite PR Checker");
        assertThat(out.get("atBlip").get("text").asText()).contains("Auto re-run status is unknown");
        assertThat(out.get("atBlip").get("shown").asInt()).isZero();
        assertThat(out.get("atBlip").get("banner").asBoolean()).isTrue();
        assertThat(out.get("askedAfter").asInt()).isPositive();
        assertThat(out.get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
        assertThat(out.get("shown")).hasSize(1);
    }

    /** The checker's link opened in a background tab while re-runs went, the verdict answered after the runs. */
    @Test
    void aTabOpenedInTheBackgroundLearnsTheVerdictIsFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + HELD.formatted("analyze") + """
            page.route('/api/analyze', { body: verdict({ buildId: 9100 }) });
            page.route('/api/settling', { body: %s });
            await page.setHidden(true);
            await page.load('?pr=13575');
            release();
            await page.settle();
            const settling = page.run('document').title;
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(900000);
            report({ settling, asked: page.fetches('/api/runs').length, title: page.run('document').title });
            """.formatted(SETTLING));

        assertThat(out.get("settling").asText()).isEqualTo("♻️ re-run 1/2 · #13575 — Ignite PR Checker");
        assertThat(out.get("asked").asInt()).isGreaterThan(1);
        assertThat(out.get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
    }

    /**
     * The hidden tab does not re-read the verdict while the chain runs: once it finished, the verdict of the run
     * before, 9001 with no blockers, read as final until the new one came.
     */
    @Test
    void theVerdictOfTheRunBeforeIsNotCalledFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + HELD.formatted("refresh") + """
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            page.route('/api/analyze', { body: verdict({ blockers: [] }) });
            await page.load('?pr=13575');
            await page.setHidden(true);
            page.route('/api/runs', { body: [] });
            page.route('/api/settling', url => ({ body: url.includes('build=9100') ? %s
                : { phase: 'final', wave: 0, of: 2 } }));
            page.route('/api/refresh', { body: verdict({ buildId: 9100 }) }, 'POST');
            await page.tick(90000);
            const analysing = { title: page.run('document').title, icon: %s, text: page.el('settling').textContent };
            release();
            await page.settle();
            report({ analysing, after: page.run('document').title });
            """.formatted(SETTLING, ICON));

        assertThat(out.get("analysing").get("title").asText()).isEqualTo("⌛ analysing · #13575 — Ignite PR Checker");
        assertThat(out.get("analysing").get("icon").asText()).contains("%231cb6ed");
        assertThat(out.get("analysing").get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("The runs finished. The checker is updating the verdict.").doesNotContain("Final verdict.");
        assertThat(out.get("after").asText()).isEqualTo("♻️ re-run 1/2 · #13575 — Ignite PR Checker");
    }

    /** The refresh after the chain failed, and so did the look after it: the verdict is asked for again. */
    @Test
    void aVerdictThatDidNotComeIsAskedForAgain() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + """
            await page.load('?pr=13575');
            await page.setHidden(true);
            page.route('/api/runs', { body: [] });
            page.route('/api/refresh', { status: 502, body: {} }, 'POST');
            page.route('/api/analyze', { status: 502, body: {} });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(30000);
            const failed = { title: page.run('document').title, text: page.el('settling').textContent };
            page.route('/api/analyze', { body: verdict({ buildId: 9100, blockers: [
                blocker('ClientReconnectTest.testReconnect', 'occ-1'), blocker('CacheTest.testPut', 'occ-2')] }) });
            await page.tick(180000);
            report({ failed, title: page.run('document').title });
            """);

        assertThat(out.get("failed").get("title").asText()).isEqualTo("⌛ analysing · #13575 — Ignite PR Checker");
        assertThat(out.get("failed").get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("checker is updating the verdict").doesNotContain("Final verdict.");
        assertThat(out.get("title").asText()).isEqualTo("❌ 2 blockers · #13575 — Ignite PR Checker");
    }

    /** The session ended while an answer about the run was on its way: the login form asks nothing. */
    @Test
    void aSignedOutPageAsksNothingMore() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/settling', { body: %s });
            await page.load('?pr=13575');
            %s
            await page.tick(90000);
            page.route('/api/runs', { status: 401, body: {} });
            await page.tick(90000);
            const signedOut = page.fetches('/api/runs').length;
            release();
            await page.tick(600000);
            report({ login: !page.el('loginView').classList.contains('hidden'),
                asked: page.fetches('/api/runs').length - signedOut });
            """.formatted(SETTLING, HELD.formatted("settling")));

        assertThat(out.get("login").asBoolean()).isTrue();
        assertThat(out.get("asked").asInt()).isZero();
    }

    /** /api/settling failed on its first answer about the new run: nothing says its verdict is final yet. */
    @Test
    void aVerdictNotKnownToBeFinalRaisesNoNotification() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + NOTIFICATIONS + """
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            %s
            page.route('/api/settling', { status: 502, body: {} });
            await page.tick(15000);
            const unknown = { title: page.run('document').title, shown: shown.length };
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(90000);
            report({ unknown, shown });
            """.formatted(FINISHED));

        assertThat(out.get("unknown").get("title").asText()).isEqualTo("#13575 — Ignite PR Checker");
        assertThat(out.get("unknown").get("shown").asInt()).isZero();
        assertThat(out.get("shown")).hasSize(1);
        assertThat(out.get("shown").get(0).get("body").asText()).isEqualTo("The verdict of RunAll 9100 is final.");
    }

    /** "Notify me" was for RunAll 9100, which was cancelled: the verdict on screen stays the one of 9001. */
    @Test
    void theVerdictOfTheRunBeforeRaisesNoNotification() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNNING + NOTIFICATIONS + """
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            page.route('/api/runs', { body: [] });
            page.route('/api/refresh', { body: verdict() }, 'POST');
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2 } });
            await page.tick(15000);
            await page.tick(90000);
            report({ title: page.run('document').title, shown });
            """);

        assertThat(out.get("title").asText()).isEqualTo("❌ 1 blocker · #13575 — Ignite PR Checker");
        assertThat(out.get("shown")).isEmpty();
    }

    /**
     * Given a live leaf-suite re-run and an old final receipt, when the page polls and notifications are armed,
     * then the tab and visible verdict wait for the suite instead of announcing a final result.
     */
    @Test
    void aLiveLeafSuiteKeepsTheVerdictAndNotificationWaiting() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + NOTIFICATIONS + """
            page.route('/api/runs', { body: [{ buildId: 9100, state: 'running', name: 'Cache', runAll: false,
                webUrl: 'https://ci2.example/build/9100' }] });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            await page.tick(15000);
            report({ title: page.run('document').title, text: page.el('settling').textContent, shown });
            """);

        assertThat(out.get("title").asText()).startsWith("▶ running");
        assertThat(out.get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("Other runs of this PR: 1 running, 0 queued.").doesNotContain("Final verdict.");
        assertThat(out.get("shown")).isEmpty();
    }

    /**
     * Given a final receipt but a retryable incomplete analysis, when the retry deadline passes, then only the
     * updated final state may notify; the receipt cannot hide the earlier analysis wait.
     */
    @Test
    void anIncompleteAnalysisKeepsTheFinalReceiptFromNotifyingEarly() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + NOTIFICATIONS + """
            page.route('/api/analyze', { body: verdict({ incompleteSince: page.now() - 19 * 60000 }) });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            const pending = { shown: shown.length, title: page.run('document').title,
                text: page.el('settling').textContent };
            await page.tick(90000);
            report({ pending, shown, after: page.el('settling').textContent });
            """);

        assertThat(out.get("pending").get("shown").asInt()).isZero();
        assertThat(out.get("pending").get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("TeamCity errors", "retrying");
        assertThat(out.get("pending").get("title").asText()).isEqualTo("#13575 — Ignite PR Checker");
        assertThat(out.get("shown")).hasSize(1);
        assertThat(out.get("after").asText()).startsWith("Final verdict.");
    }

    /**
     * Given a clean-looking result whose head check is still pending, when a final receipt arrives first,
     * then the page waits for that check before declaring or notifying the final verdict.
     */
    @Test
    void theHeadCheckMustFinishBeforeAFinalCleanNotification() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + NOTIFICATIONS
            + HELD.formatted("pending") + """
            page.route('/api/analyze', { body: verdict({ blockers: [], standing: 'CLEAN' }) });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 0 } });
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            const pending = { shown: shown.length, text: page.el('settling').textContent,
                title: page.run('document').title };
            release();
            await page.settle();
            await page.tick(90000);
            report({ pending, shown, after: page.el('settling').textContent });
            """);

        assertThat(out.get("pending").get("shown").asInt()).isZero();
        assertThat(out.get("pending").get("text").asText()).startsWith("Preliminary verdict — wait")
            .contains("verifies that this run tested the PR's latest commit");
        assertThat(out.get("pending").get("title").asText()).isEqualTo("#13575 — Ignite PR Checker");
        assertThat(out.get("shown")).hasSize(1);
        assertThat(out.get("after").asText()).startsWith("Final verdict.");
    }

    /**
     * Given a final verdict with a confirmed empty run list, when the next GET fails, then final notifications and
     * manual posting wait for a successful read instead of trusting that stale empty list.
     */
    @Test
    void aFailedRunReadRevokesFinalUntilTheListIsConfirmedAgain() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + NOTIFICATIONS + """
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 2 } });
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            page.route('/api/visa', { body: { state: 'due', starter: 'alice', mine: true, jiraReady: true } });
            page.route('/api/pr-comment', { body: { bot: 'ready', url: null } });
            await page.load('?pr=13575');
            // Nothing to press: the button is grey, or the group has none while the starter's option still posts.
            const off = id => page.el(id).classList.contains('hidden')
                || page.el(id).getAttribute('aria-disabled') === 'true';
            const state = () => ({ text: page.el('settling').textContent, title: page.run('document').title,
                visaShown: !page.el('visaPostGroup').classList.contains('hidden'),
                prShown: !page.el('prPostGroup').classList.contains('hidden'),
                visaOff: off('visaPost'), prOff: off('prPost'), shown: shown.length });
            const before = state();
            page.route('/api/runs', { status: 502, body: {} });
            await page.run('loadRuns')(13575);
            await page.settle();
            await page.el('notifyBtn').onclick();
            await page.settle();
            const failed = state();
            await page.tick(90000);
            const stillFailed = state();
            page.route('/api/runs', { body: [] });
            await page.run('loadRuns')(13575);
            await page.settle();
            report({ before, failed, stillFailed, recovered: state() });
            """);

        assertThat(out.at("/before/text").asText()).startsWith("Final verdict.");
        assertThat(out.at("/before/visaShown").asBoolean()).isTrue();
        assertThat(out.at("/before/prShown").asBoolean()).isTrue();
        assertThat(out.at("/before/visaOff").asBoolean()).isFalse();
        assertThat(out.at("/before/prOff").asBoolean()).isFalse();
        for (String stage : new String[] { "failed", "stillFailed" }) {
            assertThat(out.at("/" + stage + "/text").asText()).startsWith("Preliminary verdict — wait")
                .contains("Current runs could not be confirmed").doesNotContain("Final verdict.");
            assertThat(out.at("/" + stage + "/title").asText()).isEqualTo("#13575 — Ignite PR Checker");
            assertThat(out.at("/" + stage + "/visaOff").asBoolean()).isTrue();
            assertThat(out.at("/" + stage + "/prOff").asBoolean()).isTrue();
            assertThat(out.at("/" + stage + "/shown").asInt()).isZero();
        }
        assertThat(out.at("/recovered/text").asText()).startsWith("Final verdict.");
        assertThat(out.at("/recovered/visaOff").asBoolean()).isFalse();
        assertThat(out.at("/recovered/prOff").asBoolean()).isFalse();
        assertThat(out.at("/recovered/shown").asInt()).isEqualTo(1);
    }

    /**
     * Given a positive final receipt but no successful run-list GET yet, when the PR opens, then a missing run list
     * cannot be mistaken for a confirmed empty list or enable final notification and manual posting.
     */
    @Test
    void theFirstRunReadMustSucceedBeforeTheVerdictIsFinal() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + NOTIFICATIONS + HELD.formatted("runs") + """
            page.route('/api/runs', { status: 502, body: {} });
            page.route('/api/settling', { body: { phase: 'final', wave: 0, of: 2, completedWaves: 0 } });
            page.route('/api/pr-title', { body: { title: 'IGNITE-29049 Move MDC to JUnit' } });
            page.route('/api/visa', { body: { state: 'due', starter: 'alice', mine: true, jiraReady: true } });
            page.route('/api/pr-comment', { body: { bot: 'ready', url: null } });
            await page.load('?pr=13575');
            await page.el('notifyBtn').onclick();
            await page.settle();
            const off = id => page.el(id).classList.contains('hidden')
                || page.el(id).getAttribute('aria-disabled') === 'true';
            const state = () => ({ text: page.el('settling').textContent, title: page.run('document').title,
                visaOff: off('visaPost'), prOff: off('prPost'), shown: shown.length });
            const pending = state();
            release();
            await page.settle();
            report({ pending, failed: state() });
            """);

        for (String stage : new String[] { "pending", "failed" }) {
            assertThat(out.at("/" + stage + "/text").asText()).startsWith("Preliminary verdict — wait")
                .contains("Current runs could not be confirmed").doesNotContain("Final verdict.");
            assertThat(out.at("/" + stage + "/title").asText()).isEqualTo("#13575 — Ignite PR Checker");
            assertThat(out.at("/" + stage + "/visaOff").asBoolean()).isTrue();
            assertThat(out.at("/" + stage + "/prOff").asBoolean()).isTrue();
            assertThat(out.at("/" + stage + "/shown").asInt()).isZero();
        }
    }
}
