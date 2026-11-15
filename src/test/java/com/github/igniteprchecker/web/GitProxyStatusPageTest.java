package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The Git proxy block reports the switch, current requests and each public repository's last request result. */
class GitProxyStatusPageTest {
    /** Six public repositories expose current request counts and ISO timestamps; PHP has not been requested yet. */
    private static final String PROXY = """
        const repository = slug => ({ slug, activeGitRequests: 0,
            lastSuccessAt: new Date(page.now() - 120000).toISOString(), lastRequestFailed: false });
        const proxy = { enabled: true, activeGitRequests: 1, repositories: [repository('ignite'),
            repository('ignite-python-thin-client'),
            { slug: 'ignite-php-thin-client', activeGitRequests: 0, lastSuccessAt: null, lastRequestFailed: false },
            repository('ignite-nodejs-thin-client'), repository('ignite-release'), repository('ignite-extensions')] };
        proxy.repositories[0].activeGitRequests = 1;
        page.route('/api/git-proxy/status', { body: proxy });
        const seen = () => ({ cards: page.text('proxyCards').replace(/\\u00a0/g, ' '), repos: page.text('proxyRepos'),
            html: page.el('proxyRepos').innerHTML });
        """;

    /** Given an enabled proxy, the block shows the total, per-repository requests and the last success times. */
    @Test
    void enabledShowsActivityAndAllPublicRepositories() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("Git proxyOn 1 active Git request");
        assertThat(out.get("repos").asText()).contains("Ignite1 active Git request", "Python0 active Git requests",
            "PHP0 active Git requests", "No successful Git request yet", "Last success 2m ago", "Node.js",
            "Ignite release", "Ignite extensions").doesNotContain("NaN", "Ready", "preparation", "Syncing");
    }

    /** Given two active requests, completing them updates both the total and repository counts at the next poll. */
    @Test
    void currentRequestsDisappearAfterTheyFinish() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            proxy.activeGitRequests = 2;
            proxy.repositories[2].activeGitRequests = 1;
            await page.load('');
            const during = seen();
            proxy.activeGitRequests = 0;
            for (const repo of proxy.repositories) repo.activeGitRequests = 0;
            proxy.repositories[2].lastSuccessAt = new Date(page.now()).toISOString();
            await page.tick(5000);
            report({ ...seen(), during });
            """);

        assertThat(out.get("during").get("cards").asText()).contains("2 active Git requests");
        assertThat(out.get("during").get("repos").asText()).contains("PHP1 active Git request");
        assertThat(out.get("cards").asText()).contains("0 active Git requests");
        assertThat(out.get("repos").asText()).contains("Ignite0 active Git requests", "PHP0 active Git requests")
            .doesNotContain("No successful Git request yet");
    }

    /** Given a failed request after an earlier success, both the failure and the previous success remain visible. */
    @Test
    void aFailedRequestKeepsTheLastSuccessfulTime() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            proxy.repositories[0].lastRequestFailed = true;
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("repos").asText()).contains("Last Git request failed", "Last success 2m ago");
        assertThat(out.get("html").asText()).contains("class=\"bad\"");
    }

    /** Given the proxy disabled, the block shows Off and zero activity without claiming where TeamCity fetches code. */
    @Test
    void disabledShowsOffAndNoActivity() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            proxy.enabled = false;
            proxy.activeGitRequests = 0;
            for (const repo of proxy.repositories) repo.activeGitRequests = 0;
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("Git proxyOff 0 active Git requests");
        assertThat(out.get("repos").asText()).contains("Ignite0 active Git requests", "PHP0 active Git requests");
        assertThat(out.toString()).doesNotContain("TeamCity uses GitHub");
    }

    /** Given a displayed activity count, a failed next poll marks status unavailable instead of reporting stale activity. */
    @Test
    void failedPollClearsTheActivityCount() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            await page.load('');
            page.route('/api/git-proxy/status', { status: 503, body: {} });
            await page.tick(5000);
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("unavailable", "Could not read Git proxy status")
            .doesNotContain("active Git requests");
        assertThat(out.get("repos").asText()).isEmpty();
    }

    /** Given Russian selected, the block translates request activity, failure and service repository labels. */
    @Test
    void theProxyBlockSpeaksRussian() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json")) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/page-language', { body: { lang: 'ru' } });
            proxy.repositories[0].lastRequestFailed = true;
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("Git-прокси", "ВКЛ", "1 активный Git-запрос");
        assertThat(out.get("repos").asText()).contains("Последний Git-запрос завершился ошибкой",
            "Релизы Ignite", "Расширения Ignite");
    }
}
