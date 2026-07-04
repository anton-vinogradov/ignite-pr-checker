package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GithubClientPersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private GithubClient client() {
        return new GithubClient(new GithubProperties("apache/ignite", null, 300), mapper, new Metrics(mapper));
    }

    @Test
    void restoresPrListFromSnapshotWithoutHittingGithub(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("github.json");
        // A fresh cacheTs means openPrs() serves the restored list and never calls the network.
        Files.writeString(file, "{\"prs\":[{\"number\":10,\"title\":\"Fix\",\"url\":\"https://x/10\"}],"
            + "\"cacheTs\":" + System.currentTimeMillis() + "}");

        GithubClient loaded = client();
        loaded.loadFrom(file);

        assertThat(loaded.openPrs()).containsExactly(new PrSummary(10, "Fix", "https://x/10"));
    }

    @Test
    void saveThenLoadRoundTrips(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        Files.writeString(a, "{\"prs\":[{\"number\":1,\"title\":\"T\",\"url\":\"u\"}],"
            + "\"cacheTs\":" + System.currentTimeMillis() + "}");

        GithubClient first = client();
        first.loadFrom(a);
        first.saveTo(b);

        GithubClient second = client();
        second.loadFrom(b);

        assertThat(second.openPrs()).containsExactly(new PrSummary(1, "T", "u"));
    }

    @Test
    void saveWithEmptyCacheWritesNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("github.json");

        client().saveTo(file); // cache is null (never loaded/fetched)

        assertThat(Files.exists(file)).isFalse();
    }
}
