package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
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

    public ConfigController(TeamcityProperties teamcity, GithubProperties github, GithubClient githubClient) {
        this.teamcity = teamcity;
        this.github = github;
        this.githubClient = githubClient;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "teamcityUrl", teamcity.baseUrl(),
            "githubRepo", github.repo(),
            "starCount", githubClient.starCount());
    }
}
