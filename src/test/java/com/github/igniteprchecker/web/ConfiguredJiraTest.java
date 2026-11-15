package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.config.ThinClientProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.ThinClients;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.metrics.Metrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An instance with its own jira.base-url posted visas there, while the PR page linked every IGNITE ticket to
 * issues.apache.org: the page now takes the JIRA from the server, as it takes the repository.
 */
class ConfiguredJiraTest {
    @Test
    void pageLearnsTheJiraTheVisasGoTo() {
        ConfigController config = new ConfigController(new TeamcityProperties("https://ci2.example/"),
            new GithubProperties(null, null, null), mock(GithubClient.class),
            new AnalysisProperties(null, "RunAll", null, null, null, 300, null), mock(SuiteBaseline.class),
            new JiraClient("https://jira.example/", Duration.ofSeconds(5), new Metrics(Jackson2Defaults.mapper())));

        assertThat(config.config()).containsEntry("jiraUrl", "https://jira.example");
    }

    /** Given configured projects, the catalog lists their repos, visible build names and composite mode. */
    @Test
    void pageLearnsEveryClientsRepoAndSuite() {
        ConfigController config = new ConfigController(new TeamcityProperties("https://ci2.example/"),
            new GithubProperties(null, null, null), mock(GithubClient.class),
            new AnalysisProperties(null, "RunAll", null, null, null, 300, null), mock(SuiteBaseline.class),
            new JiraClient("https://jira.example/", Duration.ofSeconds(5), new Metrics(Jackson2Defaults.mapper())),
            new ThinClients(new PythonClientProperties("example/python-client", "Python_Client_Suite", null, null),
                new ThinClientProperties(null, null)));

        JsonNode response = Jackson2Defaults.mapper().valueToTree(config.config());

        assertThat(response.path("clients")).isEqualTo(Jackson2Defaults.mapper().readTree("""
            [
              {"key":"python","label":"Python","repo":"example/python-client","suite":"Python_Client_Suite","composite":false},
              {"key":"php","label":"PHP","repo":"apache/ignite-php-thin-client","suite":"Thin client: PHP","composite":false},
              {"key":"nodejs","label":"Node.js","repo":"apache/ignite-nodejs-thin-client","suite":"Thin client: Node.js","composite":false},
              {"key":"extensions","label":"Extensions","repo":"apache/ignite-extensions","suite":"Extensions: All Tests","composite":true}
            ]
            """));
        assertThat(config.config()).doesNotContainKeys("clientRepo", "clientSuite");
    }
}
