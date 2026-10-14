package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * pr-commands.json remembers handled comments, the narrated runs and who was already onboarded. The
 * file v1.20.11 wrote must read back whole: a lost "handled" re-runs a command, a lost "onboarded"
 * replies to the same person again. A story told in the commander's own comment, with their token, is the one thing
 * not told on: the checker writes only to its own comments.
 */
class PrCommandsSnapshotTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final GithubClient github = AppWriting.github();

    private final StandingVisas standing = mock(StandingVisas.class);

    private final PrCommands commands = new PrCommands(mapper, github, standing, mock(TcClient.class),
        mock(RerunTracker.class), mock(StyleFixService.class), mock(SuiteBaseline.class), "https://checker.example",
        new TeamcityProperties("https://ci2.example/"));

    @Test
    void theSnapshotOfTheCurrentReleaseReadsBackWhole(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pr-commands.json");
        try (InputStream in = getClass().getResourceAsStream("/snapshots/pr-commands-v1.20.11.json")) {
            Files.copy(in, file);
        }

        commands.loadFrom(file);
        Path saved = dir.resolve("saved.json");
        commands.saveTo(saved);

        JsonNode old = mapper.readTree(file.toFile());
        JsonNode now = mapper.readTree(saved.toFile());
        assertThat(now.get("narrations").isEmpty()).as("its story lives in the commander's own comment").isTrue();
        ((ObjectNode)old).remove("watching");
        assertKeeps(old, now, "$");
        assertThat(commands.handledCount()).isEqualTo(42);
    }

    @Test
    void someoneOnboardedBeforeTheUpgradeIsNotRepliedToAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pr-commands.json");
        try (InputStream in = getClass().getResourceAsStream("/snapshots/pr-commands-v1.20.11.json")) {
            Files.copy(in, file);
        }
        commands.loadFrom(file);
        when(standing.anyGhEnrolled()).thenReturn(true);
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(7L,
            "/run-all", "https://github.com/apache/ignite/pull/13800#issuecomment-7", Instant.now().toString(),
            GhUsers.of("nizhikov"))));

        commands.poll();

        verify(github, never()).appComment(anyInt(), any(), anyString());
    }

    private static void assertKeeps(JsonNode old, JsonNode now, String path) {
        if (old.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = old.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                assertThat(now.has(f.getKey())).as(path + "." + f.getKey()).isTrue();
                assertKeeps(f.getValue(), now.get(f.getKey()), path + "." + f.getKey());
            }
        }
        else
            assertThat(now).as(path).isEqualTo(old);
    }
}
