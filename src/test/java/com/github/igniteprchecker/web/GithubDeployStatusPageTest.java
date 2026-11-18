package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/** The real status page shows deployment progress and keeps the preceding success visible after a failure. */
class GithubDeployStatusPageTest {
    /** Supplies the existing status endpoint and a preceding successful deployment, without network calls. */
    private static final String STATUS = """
        page.el('tcStack').getContext = () => new Proxy({}, { get: (target, key) => key === 'measureText'
            ? () => ({ width: 16 }) : () => {} });
        const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
        const status = { version: '1.52.0', signedIn: false, uptimeSeconds: 60, startedAt: page.now() - 60000,
            health: 'ok', logHealth: 'ok', healthProblems: [],
            teamcity: { lastHour, sinceStart: 0 }, github: { lastHour, sinceStart: 0 },
            jvm: { heapUsedMb: 120, heapMaxMb: 512, threads: 40, cpus: 2 },
            app: { openPrs: 3, pooledTokens: 1 }, log: { errors: 0, warnings: 0, clientMistakes: 0, recent: [] } };
        const deployment = { state: 'starting', version: '1.52.0-7-gabcdef0', commit: 'abcdef01'.repeat(5),
            startedAt: '2026-11-18T10:00:00Z', lastSuccessAt: '2026-11-17T09:00:00Z', lastSuccessVersion: '1.51.0' };
        page.route('/api/status', { body: status });
        const seen = () => ({ text: page.text('githubDeployCards'), html: page.el('githubDeployCards').innerHTML,
            error: page.text('err'), polls: page.fetches('/api/status').length });
        """;

    /** Given an active deployment, loading status shows its target, start time and the previous success. */
    @ParameterizedTest
    @CsvSource({"downloading,Downloading", "starting,Starting"})
    void anActiveDeploymentNamesItsTargetAndPreviousSuccess(String state, String label) throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.githubDeploy = { ...deployment, state: '%s' };
            await page.load('');
            report(seen());
            """.formatted(state));

        assertThat(out.get("text").asText()).contains(label, "1.52.0-7-gabcdef0", "abcdef0", "Started",
            "Last successful deployment", "1.51.0", "2026").doesNotContain("undefined", "NaN");
        assertThat(out.get("html").asText()).contains("v warn", "title=\"" + "abcdef01".repeat(5) + "\"");
        assertThat(out.get("error").asText()).isEmpty();
    }

    /** Given failure or rollback, the visible reason explains the attempt while the earlier success stays visible. */
    @ParameterizedTest
    @CsvSource({"failed,artifact-invalid,Failed,The downloaded artifact failed validation.",
        "rolled_back,health-check-failed,Rolled back,The new version did not pass its health check."})
    void aFailedAttemptKeepsTheLastSuccessfulDeployment(String state, String detail, String label, String reason)
        throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.githubDeploy = { ...deployment, state: '%s', detail: '%s', finishedAt: '2026-11-18T10:01:00Z' };
            await page.load('');
            report(seen());
            """.formatted(state, detail));

        assertThat(out.get("text").asText()).contains(label, reason, "Finished", "1.52.0-7-gabcdef0",
            "Last successful deployment", "1.51.0");
    }

    /** Given no initial record, the existing status poll later replaces it with a successful deployment. */
    @Test
    void theExistingPollShowsTheNewlyRecordedSuccess() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            const before = seen();
            status.githubDeploy = { ...deployment, state: 'succeeded', finishedAt: '2026-11-18T10:01:00Z',
                lastSuccessAt: '2026-11-18T10:01:00Z', lastSuccessVersion: deployment.version };
            await page.tick(5000);
            report({ before, after: seen(), deployCalls: page.fetches('/api/').filter(f => f.url.includes('deploy')) });
            """);

        assertThat(out.get("before").get("text").asText()).contains("Not recorded");
        assertThat(out.get("after").get("text").asText()).contains("Succeeded", "Last successful deployment",
            "1.52.0-7-gabcdef0").doesNotContain("Not recorded");
        assertThat(out.get("after").get("polls").asInt()).isEqualTo(2);
        assertThat(out.get("deployCalls")).isEmpty();
    }

    /** Given an unreadable helper record, the page shows unavailable rather than a successful or active install. */
    @Test
    void anUnreadableRecordDoesNotClaimDeploymentProgress() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            status.githubDeploy = { state: 'unavailable', detail: 'status-unreadable' };
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("text").asText()).contains("unavailable", "Could not read deployment status.")
            .doesNotContain("Starting", "Succeeded", "Not recorded");
    }

    /** Given Russian selected, rollback and its cause read in Russian together with both deployment dates. */
    @Test
    void theDeploymentBlockSpeaksRussian() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json")) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/page-language', { body: { lang: 'ru' } });
            status.githubDeploy = { ...deployment, state: 'rolled_back', detail: 'health-check-failed',
                finishedAt: '2026-11-18T10:01:00Z' };
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("text").asText()).contains("Деплой из GitHub", "Выполнен откат", "Начало:",
            "Завершение:", "Новая версия не прошла проверку работоспособности.", "Последний успешный деплой")
            .doesNotContain("Rolled back", "Started", "Finished", "undefined");
    }
}
