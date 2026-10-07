package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.metrics.Metrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

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
            new JiraClient("https://jira.example/", Duration.ofSeconds(5), new Metrics(new ObjectMapper())));

        assertThat(config.config()).containsEntry("jiraUrl", "https://jira.example");
    }
}
