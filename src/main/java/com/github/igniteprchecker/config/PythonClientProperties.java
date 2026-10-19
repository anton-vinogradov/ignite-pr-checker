package com.github.igniteprchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PRs of the Python thin client. A {@code /run-all} in a PR of {@code repo} queues one TeamCity suite, {@code suite},
 * with the PR's head of the client and Ignite's master. The suite takes the two from two VCS roots, named by their VCS
 * root ids {@code igniteVcsRoot} and {@code clientVcsRoot}; the ids of their instances are looked up in TeamCity, as
 * they differ between servers. An empty value takes its default.
 */
@ConfigurationProperties(prefix = "python-client")
public record PythonClientProperties(String repo, String suite, String igniteVcsRoot, String clientVcsRoot) {
    /** The id of the suite on ci2 that runs the client's tests. */
    public static final String DEFAULT_SUITE = "IgniteTests24Java8_WiPThinClientPython";

    public PythonClientProperties {
        repo = orElse(repo, "apache/ignite-python-thin-client");
        suite = orElse(suite, DEFAULT_SUITE);
        igniteVcsRoot = orElse(igniteVcsRoot, "GitHubApacheIgnite");
        clientVcsRoot = orElse(clientVcsRoot, "GitHubApacheIgnitePythonThinClient");
    }

    private static String orElse(String value, String otherwise) {
        return value == null || value.isBlank() ? otherwise : value.strip();
    }
}
