package com.github.igniteprchecker.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import org.springframework.web.client.RestClient;

/** A real GitHub client whose calls go to a test's stub, for tests outside this package. */
public final class StubbedGithub {
    private StubbedGithub() {
    }

    public static GithubClient over(GithubProperties props, RestClient http) {
        ObjectMapper mapper = new ObjectMapper();

        return new GithubClient(props, mapper, new Metrics(mapper), http);
    }
}
