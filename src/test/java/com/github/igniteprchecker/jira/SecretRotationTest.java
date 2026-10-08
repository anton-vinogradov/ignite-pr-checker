package com.github.igniteprchecker.jira;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The README said that after a change of SESSION_SECRET the standing options stay on and wait for their owner to log
 * in again. They do not wait: the owner's next finished run drops all their options, the GitHub login and PR commands
 * included, when the checker cannot read the TeamCity token, or the JIRA token while auto-visa is on. Runs the
 * options of PR 13335's author through a restart with a new secret.
 */
class SecretRotationTest {
    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private StandingVisas rotated;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        when(jira.myself(anyString())).thenReturn(Optional.of("Anton Vinogradov"));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(tc.triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(build(9389100L, null));
        when(tc.getBuildState(anyString(), anyLong())).thenReturn(new TcModel.Build(9389100L, null, "queued", null,
            null, null, null, null, null, null, "20991231T000000+0000", null, null, null, null, null, null, null));

        StandingVisas before = standing("old-secret");
        before.change("alice", "alice-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        before.linkGhLogin("alice", "alice-tc", "alice-gh");
        before.change("bob", "bob-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        before.change("carol", "carol-tc", "carol-jira", null,
            new StandingVisas.OptionChange(true, true, false, false, null));
        Path file = dir.resolve("standing-visas.json");
        before.saveTo(file);

        rotated = standing("new-secret");
        rotated.loadFrom(file);
        rotated.tcTokenAccepted("bob", "bob-tc");
    }

    @Test
    void aRunThatFinishesBeforeItsOwnerLogsInDropsAllTheirOptions() {
        finishedRunOf("alice");

        rotated.sweep();

        assertThat(rotated.enabled("alice")).isFalse();
        assertThat(rotated.actorByGhLogin("alice-gh")).as("PR commands go with the options").isEmpty();
        assertThat(rotated.rerunOn("bob")).as("bob logged in again").isTrue();
    }

    @Test
    void loggingInAgainKeepsAutoRerunGoing() {
        rotated.tcTokenAccepted("alice", "alice-tc");
        finishedRunOf("alice");

        rotated.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("alice-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
        assertThat(rotated.rerunOn("alice")).isTrue();
        assertThat(rotated.commandsOn("alice")).isTrue();
    }

    @Test
    void autoVisaNeedsAFreshJiraTokenToo() {
        rotated.tcTokenAccepted("carol", "carol-tc");
        finishedRunOf("carol");

        rotated.sweep();

        assertThat(rotated.enabled("carol")).as("the TeamCity token alone does not save them").isFalse();
    }

    @Test
    void switchingAutoVisaOffAndOnWithAFreshJiraTokenKeepsTheOptions() {
        rotated.tcTokenAccepted("carol", "carol-tc");
        rotated.change("carol", "carol-tc", null, null, new StandingVisas.OptionChange(false, null, null, null, null));
        rotated.change("carol", "carol-tc", "carol-jira-2", null,
            new StandingVisas.OptionChange(true, null, null, null, null));
        finishedRunOf("carol");

        rotated.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("carol-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
        assertThat(rotated.visaOn("carol")).isTrue();
        assertThat(rotated.rerunOn("carol")).isTrue();
    }

    @Test
    void bothReadmesSayTheOptionsAreDroppedAtTheNextFinishedRun() throws IOException {
        assertThat(collapsed("README.md"))
            .doesNotContain("stay on but do nothing until their owner logs in again")
            .contains("drops all their options, the linked GitHub login included, if it cannot read their TeamCity "
                + "token, or their JIRA token while auto-visa is on, or their GitHub token while the PR comment is "
                + "on.");
        assertThat(collapsed("README.ru.md"))
            .doesNotContain("остаются включёнными, но ничего не делают")
            .contains("сбрасывает все его опции вместе с привязанным логином GitHub, если не может прочитать его "
                + "токен TeamCity, или токен JIRA при включённой автовизе, или токен GitHub при включённом "
                + "комментарии в PR.");
    }

    private StandingVisas standing(String secret) {
        return new StandingVisas(new ObjectMapper(),
            new SessionCodec(new SessionProperties(false, secret), new ObjectMapper()), tc, github, analyzer, jira,
            mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class));
    }

    private void finishedRunOf(String user) {
        when(tc.findRunAllBuildForPr(anyString(), eq(PR))).thenReturn(Optional.of(build(RUN_ALL, user)));
        when(analyzer.analyzeForAction(anyString(), eq(PR))).thenReturn(Optional.of(verdict()));
    }

    private static String collapsed(String file) throws IOException {
        return Files.readString(Path.of(file), UTF_8).replaceAll("\\s+", " ");
    }

    private static TcModel.Build build(long id, String triggeredBy) {
        return new TcModel.Build(id, "FAILURE", triggeredBy == null ? "queued" : "finished", "pull/13335/head",
            null, null, null, null, null, null, null, null,
            triggeredBy == null ? null : new TcModel.Triggered("user", new TcModel.User(triggeredBy)),
            null, null, null, null, null);
    }

    private static AnalysisResult verdict() {
        TestVerdict blocker = new TestVerdict(1L, "GridCacheTest.testPut", "Cache1", 9389012L, "Cache 1", "o1",
            true, false, "not seen failing in 100 master run(s)", "F", 1);

        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 0, 0);
    }
}
