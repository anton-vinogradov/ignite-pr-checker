package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * While PRC_ADMINS was not set, anyone signed in saw the log messages on the status page, who restarted and flushed
 * last, Restart service, Flush caches and the Users tab, and anyone may register on ci2. Nothing on the pages said
 * who the operators were. Runs the pages' own scripts.
 */
class OperatorPagesTest {
    /** A signed-in viewer who is not an operator, on an instance with operators, after one logged warning. */
    private static final String STATUS = """
        page.el('tcStack').getContext = () => new Proxy({}, { get: () => () => {} });
        const lastHour = { total: 0, ok: 0, fail: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
        const status = { version: '1.38.0', signedIn: true, operator: false, operatorsNamed: true, uptimeSeconds: 60,
            startedAt: page.now() - 60000, health: 'warn', logHealth: 'warn', healthProblems: [],
            teamcity: { lastHour, sinceStart: 0 }, github: { lastHour, sinceStart: 0 },
            jvm: { heapUsedMb: 120, heapMaxMb: 512, threads: 40, cpus: 2 }, app: { openPrs: 3, pooledTokens: 1 },
            log: { errors: 0, warnings: 1, clientMistakes: 0, lastWarningAt: page.now() - 60000, recent: [] },
            config: {} };
        const warning = { t: page.now() - 60000, level: 'WARN', logger: 'StandingVisas',
            message: 'PR 13655 skipped: TeamCity rejected the token of nsamelchev' };
        page.route('/api/status', { body: status });
        page.route('/api/users', { body: [] });
        const seen = () => ({ restart: !page.el('restartBtn').hidden, flush: !page.el('flushBtn').hidden,
            tabs: !page.el('viewTabs').hidden, log: page.el('logList').textContent,
            logCard: page.el('logCards').textContent, last: page.el('flushLast').textContent });
        """;

    @Test
    void theOperatorSeesTheMessagesTheActionsAndTheUsersTab() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            Object.assign(status, { operator: true,
                admin: { last: { flush: { by: 'avinogradov', at: page.now() - 120000 } } } });
            status.log.recent = [warning];
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("restart").asBoolean()).isTrue();
        assertThat(out.get("flush").asBoolean()).isTrue();
        assertThat(out.get("tabs").asBoolean()).isTrue();
        assertThat(out.get("log").asText()).contains("TeamCity rejected the token of nsamelchev");
        assertThat(out.get("last").asText()).isEqualTo("last flush: avinogradov · 2m ago");
    }

    @Test
    void anotherSignedInViewerSeesHowManyButNotWhat() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("restart").asBoolean()).isFalse();
        assertThat(out.get("flush").asBoolean()).isFalse();
        assertThat(out.get("tabs").asBoolean()).isFalse();
        assertThat(out.get("log").asText()).isEqualTo("only operators see the messages");
        assertThat(out.get("logCard").asText()).contains("Log issues", "1 warn");
        assertThat(out.get("last").asText()).isEmpty();
    }

    @Test
    void anAnonymousViewerIsToldToLogInIfAnOperator() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            Object.assign(status, { signedIn: false });
            delete status.config;
            await page.load('');
            report(seen());
            """);

        assertThat(out.get("tabs").asBoolean()).isFalse();
        assertThat(out.get("log").asText())
            .isEqualTo("only operators see the messages: log in on the main page if you are one");
    }

    /** Nobody sees the messages then, signed in or not: the page says where they are and how to name an operator. */
    @Test
    void withoutOperatorsThePageSaysHowToNameOne() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            Object.assign(status, { operatorsNamed: false });
            await page.load('');
            const signedIn = seen();
            Object.assign(status, { signedIn: false });
            await page.tick(5000);
            report(Object.assign(signedIn, { anonymous: page.el('logList').textContent }));
            """);

        assertThat(out.get("restart").asBoolean()).isFalse();
        assertThat(out.get("tabs").asBoolean()).isFalse();
        assertThat(out.get("log").asText()).isEqualTo("No operator is named, so nobody sees the log messages here. "
            + "To name operators, set PRC_ADMINS in /etc/ignite-pr-checker/env to their TeamCity usernames, "
            + "comma-separated, and restart the service. Until then, the messages are in the service's journal: "
            + "journalctl -u ignite-pr-checker.");
        assertThat(out.get("anonymous").asText()).isEqualTo(out.get("log").asText());
    }

    /** An operator whose login ended while on the Users tab is left on a tab the page no longer shows. */
    @Test
    void aViewerNoLongerAnOperatorIsTakenOffTheUsersTab() throws Exception {
        JsonNode out = PageScript.run("status.html", STATUS + """
            Object.assign(status, { operator: true, admin: { last: {} } });
            await page.load('');
            page.el('tabUsers').listeners.click[0]();
            await page.settle();
            const usersMode = () => page.run("document.body.classList.contains('users-mode')");
            const onUsers = usersMode();
            Object.assign(status, { signedIn: false, operator: false });
            delete status.admin;
            await page.tick(5000);
            report(Object.assign(seen(), { onUsers, stillOnUsers: usersMode() }));
            """);

        assertThat(out.get("onUsers").asBoolean()).isTrue();
        assertThat(out.get("stillOnUsers").asBoolean()).isFalse();
        assertThat(out.get("tabs").asBoolean()).isFalse();
    }

    @Test
    void thePrPageMarksTheNameOfAnOperatorOnly() throws Exception {
        String scenario = """
            await page.load('');
            report({ name: page.el('username').textContent,
                mark: !page.el('operatorMark').classList.contains('hidden') });
            """;
        JsonNode operator = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'avinogradov', jira: false, admin: true } });
            """ + scenario);
        JsonNode other = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/me', { body: { username: 'alice', jira: false, admin: false } });
            """ + scenario);

        assertThat(operator.get("name").asText()).isEqualTo("avinogradov");
        assertThat(operator.get("mark").asBoolean()).isTrue();
        assertThat(other.get("mark").asBoolean()).isFalse();
    }
}
