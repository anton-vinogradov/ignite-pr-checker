package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The Git proxy block names Caddy and its read routes without inventing request statistics. */
class GitProxyStatusPageTest {
    /** Gives the page Caddy's six configured public routes without activity or health measurements. */
    private static final String PROXY = """
        const proxy = { enabled: true, engine: 'caddy', repositories: ['ignite', 'ignite-python-thin-client',
            'ignite-php-thin-client', 'ignite-nodejs-thin-client', 'ignite-release', 'ignite-extensions']
            .map(slug => ({ slug })) };
        page.route('/api/git-proxy/status', { body: proxy });
        const seen = () => ({ cards: page.text('proxyCards').replace(/\\u00a0/g, ' '), repos: page.text('proxyRepos'),
            html: page.el('proxyCards').innerHTML + page.el('proxyRepos').innerHTML,
            notes: page.staticElements(e => (e.getAttribute('class') || '').includes('proxy-note'))
                .map(e => page.text(e).replace(/\\s+/g, ' ').trim()).join(' ') });
        """;

    /** Given enabled Caddy routes, when status loads, then it shows read access and the available repositories. */
    @Test
    void enabledNamesCaddyAndItsPublicReadRoutesWithoutStatistics() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("Git proxyOn Caddy", "Read-only");
        assertThat(out.get("repos").asText()).contains("Ignite", "Python", "PHP", "Node.js",
            "Ignite release", "Ignite extensions");
        assertThat(out.get("notes").asText()).contains("stores no repositories", "runs no background sync",
            "Live Git request statistics are not available on this page.", "restore GitHub fetch URLs",
            "disable the Git route in Caddy", "GIT_PROXY_ENABLED=false");
        assertThat(out.toString()).doesNotContain("active Git request", "Last success", "Last Git request failed",
            "No successful Git request yet", "undefined", "NaN");
    }

    /** Given visible Caddy routes, when the next poll reports Off, then the card and repository list reflect it. */
    @Test
    void disabledClearsCaddyRoutesWithoutClaimingWhereTeamCityFetches() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            await page.load('');
            page.route('/api/git-proxy/status', { body: { enabled: false, engine: 'none', repositories: [] } });
            await page.tick(5000);
            report(seen());
            """);

        assertThat(out.get("cards").asText()).isEqualTo("Git proxyOff");
        assertThat(out.get("repos").asText()).isEmpty();
        assertThat(out.get("html").asText()).doesNotContain("Caddy", "Read-only", "active Git request");
        assertThat(out.toString()).doesNotContain("TeamCity uses GitHub");
    }

    /** Given visible Caddy routes, when the next poll fails, then status becomes unavailable and clears stale routes. */
    @Test
    void failedPollClearsTheEnabledStateAndRepositoryList() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            await page.load('');
            page.route('/api/git-proxy/status', { status: 503, body: {} });
            await page.tick(5000);
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("unavailable", "Could not read Git proxy status")
            .doesNotContain("Caddy", "Read-only", "active Git request");
        assertThat(out.get("repos").asText()).isEmpty();
    }

    /** Given Russian selected, when status loads, then the mode, limitations and shutdown steps read in Russian. */
    @Test
    void theProxyBlockSpeaksRussian() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + "const CATALOG = "
            + Files.readString(Path.of("src/main/resources/static/ru.json")) + ";\n" + """
            page.route('/ru.json', { body: CATALOG });
            page.route('/api/page-language', { body: { lang: 'ru' } });
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("Git-прокси", "ВКЛ", "Caddy", "Только чтение");
        assertThat(out.get("repos").asText()).contains("Релизы Ignite", "Расширения Ignite");
        assertThat(out.get("notes").asText()).contains("не хранит репозитории", "не синхронизирует их в фоне",
            "нет статистики текущих Git-запросов", "верни fetch URL GitHub", "выключи Git-маршрут в Caddy",
            "GIT_PROXY_ENABLED=false", "перезапусти этот сервис");
    }

    /** Given HTML in reported labels, when status renders them, then engine and repository names remain text. */
    @Test
    void escapesEngineAndRepositoryNames() throws Exception {
        JsonNode out = PageScript.run("status.html", PROXY + """
            proxy.engine = '<img src=x onerror=alert(1)>';
            proxy.repositories = [{ slug: '<svg onload=alert(2)>' }];
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("cards").asText()).contains("<img src=x onerror=alert(1)>");
        assertThat(out.get("repos").asText()).contains("<svg onload=alert(2)>");
        assertThat(out.get("html").asText()).contains("&lt;img src=x onerror=alert(1)&gt;",
            "&lt;svg onload=alert(2)&gt;").doesNotContain("<img", "<svg");
    }
}
