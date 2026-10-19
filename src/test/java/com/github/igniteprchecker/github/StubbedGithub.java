package com.github.igniteprchecker.github;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** A real GitHub client whose calls go to a test's stub, for tests outside this package. */
public final class StubbedGithub {
    private StubbedGithub() {
    }

    public static GithubClient over(GithubProperties props, RestClient http) {
        return over(props, "", http);
    }

    /** The same, of the checker users open at {@code publicUrl}. */
    public static GithubClient over(GithubProperties props, String publicUrl, RestClient http) {
        ObjectMapper mapper = Jackson2Defaults.mapper();

        return new GithubClient(props, mapper, new Metrics(mapper), publicUrl, http);
    }
}
