package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * pr-commands.json remembers handled comments, the narrated runs and whom the checker told what they lack for a
 * command. The file v1.20.11 wrote must read back whole: a lost "handled" re-runs a command. A story told in the
 * commander's own comment, with their token, is not told on: the checker writes only to its own comments. Earlier
 * versions told a person once for good and a PR once; a reply now goes once per person and PR, so of their memory
 * only the replies whose PR it names count, read from the file v1.28.0 wrote.
 */
class PrCommandsSnapshotTest {
    private final ObjectMapper mapper = Jackson2Defaults.mapper();

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
        ((ObjectNode)old).remove("onboarded");
        assertKeeps(old, now, "$");
        assertThat(commands.handledCount()).isEqualTo(42);
    }

    /** Dave-GH was told on PR 13790, and erin-gh on 13793; erin-gh's command on 13792 got no reply then. */
    @Test
    void aReplyGivenBeforeTheUpgradeIsNotGivenAgainOnItsPr(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pr-commands.json");
        try (InputStream in = getClass().getResourceAsStream("/snapshots/pr-commands-v1.28.0.json")) {
            Files.copy(in, file);
        }
        commands.loadFrom(file);
        commands.saveTo(dir.resolve("saved.json"));
        assertThat(mapper.readTree(dir.resolve("saved.json").toFile()).get("explained").properties())
            .extracting(Map.Entry::getKey).containsExactlyInAnyOrder("dave-gh#13790#link", "erin-gh#13793#link");
        when(standing.anyGhEnrolled()).thenReturn(true);
        when(standing.whyNoActor(any())).thenReturn(StandingVisas.NoActor.UNLINKED);
        when(github.recentIssueComments(anyString())).thenReturn(List.of(runAll(7L, 13790, "dave-gh"),
            runAll(8L, 13793, "erin-gh"), runAll(9L, 13792, "erin-gh"), runAll(10L, 13791, "dave-gh")));

        commands.poll();

        verify(github, never()).appComment(eq(13790), any(), anyString());
        verify(github, never()).appComment(eq(13793), any(), anyString());
        verify(github).appComment(eq(13792), eq("@erin-gh"), anyString());
        verify(github).appComment(eq(13791), eq("@dave-gh"), anyString());
        assertThat(commands.handledCount()).isEqualTo(61);
    }

    private static GithubClient.IssueComment runAll(long id, int pr, String login) {
        return new GithubClient.IssueComment(id, "/run-all", "https://github.com/apache/ignite/pull/" + pr
            + "#issuecomment-" + id, Instant.now().toString(), GhUsers.of(login));
    }

    private static void assertKeeps(JsonNode old, JsonNode now, String path) {
        if (old.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = old.properties().iterator();
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
