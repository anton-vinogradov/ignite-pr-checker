package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * "Cancel all" on a PR page stopped, with no question asked, every build people had queued on the PR
 * branch, someone else's RunAll chain included, and the runs row did not say whose each run was. On PR
 * 13575 alice runs a RunAll and has a suite re-run queued, while bob runs his own RunAll.
 */
class CancelMineTest {
    private static final String RUNS = """
        const run = (id, state, name, by, extra) => Object.assign({ buildId: id, state, name, btId: name,
            webUrl: 'https://ci2.example/build/' + id, pct: -1, leftSec: -1, startSec: -1, waitedSec: -1,
            elapsedSec: -1, by, mine: by === 'alice', runAll: name === 'RunAll' }, extra);
        const ALICE_RUNALL = run(201, 'running', 'RunAll', 'alice', { elapsedSec: 2700, leftSec: 3900 });
        const ALICE_SUITE = run(204, 'queued', 'Cache', 'alice', { startSec: 720 });
        const BOB_RUNALL = run(203, 'running', 'RunAll', 'bob', { leftSec: 600 });
        const asked = [];
        let answer = true;
        page.run('window').confirm = q => { asked.push(q); return answer; };
        page.route('/api/cancel-all', { body: { cancelled: 2 } }, 'POST');
        """;

    @Test
    void othersRunsShowWhoStartedThemAndOfferNoCancel() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNS + """
            page.route('/api/runs', { body: [ALICE_RUNALL, BOB_RUNALL] });
            await page.load('?pr=13575');
            const mixed = { runs: page.el('runs').textContent, html: page.el('runs').innerHTML,
                cancel: !page.el('cancelMine').classList.contains('hidden') };
            page.route('/api/runs', { body: [BOB_RUNALL] });
            await page.tick(15000);
            report({ mixed, onlyBobs: { runs: page.el('runs').textContent,
                cancel: !page.el('cancelMine').classList.contains('hidden') } });
            """);

        assertThat(out.at("/mixed/runs").asText()).contains("by bob").doesNotContain("by alice");
        assertThat(out.at("/mixed/html").asText())
            .contains("title=\"Started by bob — Cancel my runs leaves it alone\"");
        assertThat(out.at("/mixed/cancel").asBoolean()).isTrue();
        assertThat(out.at("/onlyBobs/runs").asText()).contains("by bob");
        assertThat(out.at("/onlyBobs/cancel").asBoolean()).as("nothing of alice's to cancel").isFalse();
    }

    @Test
    void cancelListsTheViewersRunsAndStopsOnlyThose() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNS + """
            page.route('/api/runs', { body: [ALICE_RUNALL, BOB_RUNALL, ALICE_SUITE] });
            await page.load('?pr=13575');
            await page.run('cancelMine')();
            report({ asked, cancels: page.fetches('/api/cancel-all').map(f => f.url),
                status: page.el('status').textContent });
            """);

        assertThat(out.get("asked")).hasSize(1);
        assertThat(out.get("asked").get(0).asText())
            .startsWith("Cancel your 2 runs of PR #13575?")
            .contains("• RunAll: running 45m · ~1h 05m left", "• Cache: queued · starts ~12m")
            .contains("Runs started by other people (1) keep going.")
            .doesNotContain("bob");
        assertThat(out.get("cancels")).hasSize(1);
        assertThat(out.get("cancels").get(0).asText()).isEqualTo("/api/cancel-all?pr=13575&ids=201,204");
        assertThat(out.get("status").asText()).isEqualTo("Cancelled 2 run(s).");
    }

    @Test
    void theQuestionNamesTheRunsAsTheyAreNow() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNS + """
            page.route('/api/runs', { body: [ALICE_RUNALL] });
            await page.load('?pr=13575');
            page.route('/api/runs', { body: [ALICE_RUNALL, ALICE_SUITE] });
            await page.run('cancelMine')();
            report({ asked, cancels: page.fetches('/api/cancel-all').map(f => f.url) });
            """);

        assertThat(out.get("asked").get(0).asText()).startsWith("Cancel your 2 runs of PR #13575?")
            .contains("• Cache: queued · starts ~12m");
        assertThat(out.get("cancels").get(0).asText()).isEqualTo("/api/cancel-all?pr=13575&ids=201,204");
    }

    @Test
    void declinedCancelStopsNothing() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + RUNS + """
            page.route('/api/runs', { body: [ALICE_RUNALL] });
            await page.load('?pr=13575');
            answer = false;
            await page.run('cancelMine')();
            report({ asked: asked.length, cancels: page.fetches('/api/cancel-all').length });
            """);

        assertThat(out.get("asked").asInt()).isEqualTo(1);
        assertThat(out.get("cancels").asInt()).isZero();
    }
}
