package com.github.igniteprchecker.persist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.UserDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The state files on prod were written by v1.20.11, and no test read one: a renamed record field would have loaded
 * as empty without a word, and the next save would have written the loss to disk. Each sample of that release must
 * load and save back with nothing lost. Unknown fields fail here, though the app itself ignores them.
 */
class ReleaseSnapshotsTest {
    private static final String RELEASE = "/snapshots/v1.20.11/";

    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().failOnUnknownProperties(true).build();

    static Stream<SnapshotCache> stateOfTheRelease() {
        SessionCodec codec = mock(SessionCodec.class);
        TcClient tc = mock(TcClient.class);
        GithubClient github = mock(GithubClient.class);
        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        JiraClient jira = mock(JiraClient.class);
        VisaService visas = mock(VisaService.class);
        Warmer warmer = mock(Warmer.class);
        PendingCommits pending = mock(PendingCommits.class);
        VisaSubscriptions subs = new VisaSubscriptions(MAPPER, codec, jira, visas, analyzer, warmer, pending);
        RerunTracker tracker = new RerunTracker(tc, warmer, subs, mock(AnalysisProperties.class), MAPPER,
            mock(ApplicationEventPublisher.class));
        StandingVisas standing = new StandingVisas(MAPPER, codec, tc, github, analyzer, jira, visas, tracker, warmer,
            pending);

        return Stream.of(standing,
            new PrCommands(MAPPER, github, standing, tc, tracker, mock(StyleFixService.class), mock(SuiteBaseline.class),
                "https://ignite-pr-checker.is-a.dev", new TeamcityProperties("https://ci2.ignite.apache.org/")),
            subs,
            tracker,
            new UserDirectory(MAPPER));
    }

    @ParameterizedTest
    @MethodSource("stateOfTheRelease")
    void loadsAndSavesBackWithNothingLost(SnapshotCache cache, @TempDir Path dir) throws IOException {
        Path sample = dir.resolve(cache.fileName());
        try (InputStream in = getClass().getResourceAsStream(RELEASE + cache.fileName())) {
            assertThat(in).as("sample of " + cache.fileName()).isNotNull();
            Files.copy(in, sample);
        }
        Path saved = dir.resolve("saved-" + cache.fileName());

        cache.loadFrom(sample);
        cache.saveTo(saved);

        assertThat(canonical(MAPPER.readTree(saved.toFile()))).isEqualTo(canonical(MAPPER.readTree(sample.toFile())));
    }

    @Test
    void whatTheServiceActsOnIsWrittenAtOnce() {
        assertThat(stateOfTheRelease().filter(SnapshotCache::durable).map(SnapshotCache::fileName))
            .containsExactly("standing-visas.json", "pr-commands.json", "visa-subs.json", "reruns.json");
    }

    /** Key order and the order of list items come from hash maps, so neither counts. */
    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> fields = new TreeMap<>();
            node.properties().forEach(f -> fields.put(f.getKey(), canonical(f.getValue())));

            return fields;
        }

        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(String.valueOf(canonical(item))));
            Collections.sort(items);

            return items;
        }

        return node.toString();
    }
}
