package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-secret client config, so the UI can deep-link to TeamCity token creation and GitHub PR pages. */
@RestController
@RequestMapping("/api")
public class ConfigController {
    private final TeamcityProperties teamcity;
    private final GithubProperties github;
    private final GithubClient githubClient;
    private final AnalysisProperties analysis;
    private final SuiteBaseline baseline;
    private final JiraClient jira;

    public ConfigController(TeamcityProperties teamcity, GithubProperties github, GithubClient githubClient,
        AnalysisProperties analysis, SuiteBaseline baseline, JiraClient jira) {
        this.teamcity = teamcity;
        this.github = github;
        this.githubClient = githubClient;
        this.analysis = analysis;
        this.baseline = baseline;
        this.jira = jira;
    }

    /**
     * {@code refreshAfterSeconds}: how old a verdict may get before a view refreshes it in the background.
     * {@code runAllSuites}: how many suites master's latest RunAll chain had (0 until known).
     * {@code jiraUrl}: the JIRA the visas go to, for the page's ticket links.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "teamcityUrl", teamcity.baseUrl(),
            "githubRepo", github.repo(),
            "starCount", githubClient.starCount(),
            "refreshAfterSeconds", analysis.refreshAfterSeconds(),
            "runAllSuites", baseline.chainSuites(),
            "jiraUrl", jira.baseUrl());
    }
}
