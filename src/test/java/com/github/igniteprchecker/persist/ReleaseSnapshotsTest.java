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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * load and save back with nothing lost; a field added since may show up in what is saved. Unknown fields fail here,
 * though the app itself ignores them.
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

        assertThat(lost(MAPPER.readTree(sample.toFile()), MAPPER.readTree(saved.toFile()), "")).isEmpty();
    }

    @Test
    void aFieldAddedSinceIsNoLossButADroppedOneIs() throws IOException {
        JsonNode sample = MAPPER.readTree("{\"subs\":[{\"pr\":13655,\"issue\":\"IGNITE-26512\"},{\"pr\":13702}]}");

        assertThat(lost(sample, MAPPER.readTree("{\"subs\":[{\"pr\":13702,\"channel\":null},"
            + "{\"pr\":13655,\"issue\":\"IGNITE-26512\",\"channel\":null}]}"), "")).isEmpty();
        assertThat(lost(sample, MAPPER.readTree("{\"subs\":[{\"pr\":13702},{\"pr\":13655}]}"), ""))
            .containsExactly("/subs/0");
    }

    @Test
    void whatTheServiceActsOnIsWrittenAtOnce() {
        assertThat(stateOfTheRelease().filter(SnapshotCache::durable).map(SnapshotCache::fileName))
            .containsExactly("standing-visas.json", "pr-commands.json", "visa-subs.json", "reruns.json");
    }

    /**
     * Where {@code saved} misses something {@code sample} holds. List items come from hash maps, so each sample item
     * may match any saved one not matched yet.
     */
    private static List<String> lost(JsonNode sample, JsonNode saved, String path) {
        if (sample.isObject()) {
            if (!saved.isObject())
                return List.of(path);

            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, JsonNode> f : sample.properties()) {
                String at = path + "/" + f.getKey();
                JsonNode kept = saved.get(f.getKey());
                missing.addAll(kept == null ? List.of(at) : lost(f.getValue(), kept, at));
            }

            return missing;
        }

        if (sample.isArray()) {
            if (!saved.isArray())
                return List.of(path);

            List<JsonNode> unmatched = new ArrayList<>();
            saved.forEach(unmatched::add);
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < sample.size(); i++) {
                JsonNode item = sample.get(i);
                Optional<JsonNode> match = unmatched.stream().filter(s -> lost(item, s, "").isEmpty()).findFirst();
                if (match.isPresent())
                    unmatched.remove(match.get());
                else
                    missing.add(path + "/" + i);
            }

            return missing;
        }

        return sample.equals(saved) ? List.of() : List.of(path);
    }
}
