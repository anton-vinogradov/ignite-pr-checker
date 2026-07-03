package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Non-secret client config, so the UI can deep-link to token creation on the right TeamCity host. */
@RestController
@RequestMapping("/api")
public class ConfigController {
    private final TeamcityProperties teamcity;

    public ConfigController(TeamcityProperties teamcity) {
        this.teamcity = teamcity;
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("teamcityUrl", teamcity.baseUrl());
    }
}
