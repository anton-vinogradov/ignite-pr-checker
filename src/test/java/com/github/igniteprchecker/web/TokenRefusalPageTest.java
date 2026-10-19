package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The times of a /run-all story came from the JIRA profile, which ⚙ never asked for: ⚙ sends the browser's time zone
 * with every change.
 */
class TokenRefusalPageTest {
    @Test
    void linkingGithubSendsTheBrowsersTimeZone() throws Exception {
        JsonNode out = PageScript.run("index.html", PageScript.SIGNED_IN + """
            page.route('/api/github/link', { body: { url: 'https://github.com/login/oauth/authorize' } }, 'POST');
            await page.load('');
            await page.el('ghLink').onclick();
            report({ posts: page.fetches('/api/github/link').map(f => f.url) });
            """);

        assertThat(out.get("posts").toString()).isEqualTo("[\"/api/github/link?tz=UTC\"]");
    }
}
