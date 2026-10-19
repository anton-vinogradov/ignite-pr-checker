package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.config.PythonClientProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.QueuedBelowTopException;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@code /run-all} in PR #69 of apache/ignite-python-thin-client runs the suite Thin client: Python with the client
 * at the PR's head and Ignite at master's head, as ci2 accepted for build 9424005, and the verdict comes back to that
 * PR. apache/ignite has a PR #69 too: the two are different PRs in commands, stories, replies and pr-commands.json.
 */
class ClientCommandTest {
    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final int PR = 69;

    private static final long RUN = 9424005L;

    private static final long CHAIN = 9424100L;

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final StandingVisas standing = new StandingVisas(mapper,
        new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github, mock(BlockerAnalyzer.class),
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    private final PrCommands commands = commands();

    @BeforeEach
    void setUp() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author");
        when(github.prHeadSha(CLIENT_REPO, PR)).thenReturn(CLIENT);
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn(IGNITE);
        when(tc.vcsRootInstances("tc", SUITE)).thenReturn(Optional.of(Map.of("GitHubApacheIgnite", "335",
            "GitHubApacheIgnitePythonThinClient", "314", "GitHubApacheIgniteRelease", "336")));
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenReturn(run("queued", null));
        when(github.appComment(eq(CLIENT_REPO), eq(PR), any(), anyString())).thenAnswer(inv ->
            new GithubClient.PostedComment(inv.getArgument(3, String.class).contains("the verdict of") ? 89L : 88L,
                "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88"));
        when(tc.triggerRunAll("tc", PR, false)).thenReturn(chain());
        when(github.appComment(eq(PR), any(), anyString())).thenReturn(new GithubClient.PostedComment(77L,
            "https://github.com/apache/ignite/pull/69#issuecomment-77"));
    }

    @Test
    void aRunAllInAClientPrRunsItsSuiteOnThePrsHeadAgainstIgniteMaster() {
        clientComment(1L, "/run-all", "Author");

        verify(tc).queuePinned("tc", new PinnedRun(SUITE, "pull/69", List.of(
            new PinnedRun.Pin("335", "refs/heads/master", IGNITE),
            new PinnedRun.Pin("314", "refs/pull/69/head", CLIENT)), Map.of("OVERRIDDEN_BRANCH", CLIENT)), false,
            "Ignite PR Checker: /run-all by @Author in https://github.com/apache/ignite-python-thin-client/pull/69");
        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "rocket");
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 **Thin client: Python queued** — [build"
            + " 9424005](https://ci2.example/viewLog.html?buildId=9424005): this PR's head `a1b2c3d` against Ignite"
            + " master at `1f2e3d4`. When the run finishes, a new comment brings you the verdict, and this story goes.");
        verify(tc, never()).triggerRunAll(anyString(), anyInt(), anyBoolean());
        verify(github, never()).appComment(anyInt(), any(), anyString());
        verify(github, never()).appReact(anyInt(), any(), anyLong(), anyString());
    }

    @Test
    void prSixtyNineOfIgniteAndOfTheClientAreTwoPrs() {
        igniteComment(1L, "/run-all", "Author");
        clientComment(2L, "/run-all", "Author");
        when(tc.getBuildState("tc", RUN)).thenReturn(run("queued", null));

        clientComment(3L, "/top", "Author");

        verify(tc).moveToQueueTop("tc", RUN);
        verify(tc, never()).moveToQueueTop("tc", CHAIN);
        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L),
            contains("\n⬆️ **Build 9424005 moved to the top of the queue.**"));
        verify(github, never()).appEdit(eq(PR), any(), eq(77L), contains("Superseded"));
        verify(tc).cancelOwnRunAllChains("tc", PR, "author");
        verify(tc).cancelOwnBuilds(eq("tc"), eq("pull/69"), eq("author"), any());
        verify(github).appReact(PR, "author", 1L, "rocket");
        verify(github).appReact(CLIENT_REPO, PR, "author", 2L, "rocket");
    }

    @Test
    void theVerdictComesInANewCommentWithBothCommitsAndTheStoryGoes() {
        clientComment(1L, "/run-all", "Author");
        when(tc.getBuildState("tc", RUN)).thenReturn(run("finished", "FAILURE"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(0));

        commands.updateEtas();

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(github, times(2)).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), bodies.capture());
        assertThat(bodies.getAllValues().get(1))
            .startsWith("@Author the verdict of your `/run-all`: **Thin client: Python** [build 9424005]"
                + "(https://ci2.example/build/9424005) ran this PR's head [`a1b2c3d`](https://github.com/"
                + CLIENT_REPO + "/pull/69/commits/" + CLIENT + ") against Ignite master at [`1f2e3d4`]"
                + "(https://github.com/apache/ignite/commit/" + IGNITE + ").")
            .contains("❌ **1 blocker:**\n- `test_sql.test_sql_fields_query` — not seen failing in 100 master runs;"
                + " failed the only run on this branch (1 run) · [TC](https://ci2.example/buildConfiguration/"
                + SUITE + "/9424005?buildTab=tests&expandedTest=o2#testNameId502)")
            .contains("**1 failing test is not blamed on this PR:**\n- `test_cache_config.test_get` — pre-existing:"
                + " fails 3/100 on master · [TC](")
            .doesNotContain("checker.example");
        verify(github).appDelete(CLIENT_REPO, PR, "author", 88L);
    }

    /** TeamCity errors left a test unchecked: the verdict waits for another try, and the story says so. */
    @Test
    void anIncompleteVerdictWaitsAndTheStorySaysTheRunIsAnalysed() {
        clientComment(1L, "/run-all", "Author");
        when(tc.getBuildState("tc", RUN)).thenReturn(run("finished", "FAILURE"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(System.currentTimeMillis()));

        commands.updateEtas();

        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L),
            endsWith("\n🏁 _Run finished — analysing; the verdict follows in a new comment._"));
        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), any(), anyString());
        verify(github, never()).appDelete(anyString(), anyInt(), any(), anyLong());
    }

    @Test
    void aSuiteTeamCityDoesNotHaveIsSaidInThePr() {
        when(tc.vcsRootInstances("tc", SUITE)).thenReturn(Optional.empty());

        clientComment(1L, "/run-all", "Author");

        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "confused");
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: TeamCity has no suite"
            + " IgniteTests24Java8_WiPThinClientPython._");
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void aSuiteWithoutTheClientsVcsRootIsSaidInThePr() {
        when(tc.vcsRootInstances("tc", SUITE)).thenReturn(Optional.of(Map.of("GitHubApacheIgnite", "335")));

        clientComment(1L, "/run-all", "Author");

        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: TeamCity names no VCS"
            + " root GitHubApacheIgnitePythonThinClient of suite IgniteTests24Java8_WiPThinClientPython._");
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void aRunTeamCityRefusesIsSaidInThePr() {
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenThrow(new TcResponseException(
            HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0],
                StandardCharsets.UTF_8)));

        clientComment(1L, "/run-all", "Author");

        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "confused");
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 _Nothing was queued: ci2 answered HTTP 403."
            + " Your ci2 account may not run builds here, or ci2's firewall blocked the request: try again in a few"
            + " minutes._");
    }

    @Test
    void aStrangerIsToldWhatARunAllQueuesHere() {
        clientComment(1L, "/run-all", "Someone");

        verify(github).appReact(CLIENT_REPO, PR, "@someone", 1L, "confused");
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("@someone"), endsWith("After that, `/run-all` here"
            + " queues the Python thin client suite under your ci2 account."));
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    /**
     * pr-commands.json keeps the client's runs apart, with the commits and OVERRIDDEN_BRANCH each was queued with: a
     * version that knows only Ignite reads its own fields and never takes the client's #69 for Ignite's. After a
     * restart the client's story goes on in the client's PR.
     */
    @Test
    void theClientsRunsAreKeptApartAndToldOnAfterARestart(@TempDir Path dir) throws Exception {
        igniteComment(1L, "/run-all", "Author");
        clientComment(2L, "/run-all", "Author");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);

        JsonNode saved = mapper.readTree(file.toFile());
        assertThat(saved.get("narrations")).hasSize(1);
        assertThat(saved.get("narrations").get(0).get("buildId").asLong()).isEqualTo(CHAIN);
        assertThat(saved.get("narrations").get(0).get("repo").isNull()).isTrue();
        JsonNode client = saved.get("clientRuns").get(0);
        assertThat(client.get("repo").asString()).isEqualTo(CLIENT_REPO);
        assertThat(client.get("buildId").asLong()).isEqualTo(RUN);
        assertThat(client.get("pinned").get("properties").get("OVERRIDDEN_BRANCH").asString()).isEqualTo(CLIENT);
        assertThat(client.get("pinned").get("revisions").findValuesAsString("version")).containsExactly(IGNITE, CLIENT);
        assertThat(client.get("pinned").get("revisions").findValuesAsString("vcsRootInstance"))
            .containsExactly("335", "314");

        PrCommands restarted = commands();
        restarted.loadFrom(file);
        when(tc.getBuildState("tc", RUN)).thenReturn(run("running", null));
        when(tc.getBuildState("tc", CHAIN)).thenReturn(chain());
        restarted.updateEtas();

        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), contains("\n⏱ _Running"));

        when(tc.getBuildState("tc", RUN)).thenReturn(run("finished", "FAILURE"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(0));
        restarted.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("ran this PR's head [`a1b2c3d`]"
            + "(https://github.com/" + CLIENT_REPO + "/pull/69/commits/" + CLIENT + ") against Ignite master at"
            + " [`1f2e3d4`](https://github.com/apache/ignite/commit/" + IGNITE + ")."));
    }

    @Test
    void aRunAllTopInAClientPrAsksForTheTop() {
        clientComment(1L, "/run-all top", "Author");

        verify(tc).queuePinned(eq("tc"), any(), eq(true), anyString());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"),
            startsWith("@Author 🚀 **Thin client: Python queued at the top of the queue** — [build 9424005]"));
    }

    /**
     * The author's ci2 account may not reorder the build queue: TeamCity queued the run, then refused to move it to the
     * top. The run counts as queued, and the story says where it waits and why.
     */
    @Test
    void aRunAllTopTeamCityQueuedAtTheEndIsAcceptedAndSaysWhy() {
        when(tc.queuePinned(eq("tc"), any(), eq(true), anyString())).thenThrow(new QueuedBelowTopException(
            run("queued", null), HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8)));

        clientComment(1L, "/run-all top", "Author");

        verify(github).appReact(CLIENT_REPO, PR, "author", 1L, "rocket");
        verify(github, never()).appReact(anyString(), anyInt(), any(), anyLong(), eq("confused"));
        verify(github).appComment(CLIENT_REPO, PR, "author", "@Author 🚀 **Thin client: Python queued, but not at the"
            + " top** — [build 9424005](https://ci2.example/viewLog.html?buildId=9424005): this PR's head `a1b2c3d`"
            + " against Ignite master at `1f2e3d4`. TeamCity refused to move it up (HTTP 403): your ci2 account may not"
            + " have the permission to reorder the build queue. When the run finishes, a new comment brings you the"
            + " verdict, and this story goes.");
    }

    /**
     * A person whose GitHub account is not linked writes /run-all in Ignite's PR #69 and then in the client's PR #69.
     * Those are two PRs, so the reply on what to do goes to each: one reply per person and PR keeps the threads quiet.
     */
    @Test
    void aStrangerToldInIgnitesPrIsToldInTheClientsPrOfTheSameNumber() {
        igniteComment(1L, "/run-all", "Someone");
        clientComment(2L, "/run-all", "Someone");

        verify(github).appComment(eq(PR), eq("@someone"), endsWith("`/run-all` here queues RunAll under your ci2"
            + " account."));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("@someone"), endsWith("`/run-all` here queues the Python"
            + " thin client suite under your ci2 account."));
    }

    /**
     * GitHub failed only the poll of the client's repo, ten minutes after the last good poll of both. The /run-all
     * written in the client's PR five minutes ago runs at the next poll: the client's window moves on only when the
     * client's own poll works, whatever Ignite's poll did.
     */
    @Test
    void aClientCommandWrittenWhileOnlyTheClientsPollFailedRuns(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path file = dir.resolve("pr-commands.json");
        Files.writeString(file, mapper.writeValueAsString(Map.of("sinceMs", now - 600_000L, "clientSinceMs",
            now - 600_000L, "handledTotal", 0)));
        commands.loadFrom(file);
        GithubClient.IssueComment runAll = new GithubClient.IssueComment(1L, "/run-all", "https://github.com/"
            + CLIENT_REPO + "/pull/69#issuecomment-1", Instant.ofEpochMilli(now - 300_000L).toString(),
            GhUsers.of("Author"));
        AtomicBoolean down = new AtomicBoolean(true);
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenAnswer(inv -> {
            if (down.get())
                throw new ResourceAccessException("I/O error on GET request for \"https://api.github.com/...\"");

            return Instant.parse(inv.getArgument(1, String.class)).toEpochMilli() <= now - 300_000L
                ? List.of(runAll) : List.of();
        });
        commands.poll();
        down.set(false);

        commands.poll();

        verify(tc).queuePinned(eq("tc"), any(), eq(false), anyString());
    }

    /**
     * GitHub asked the checker's account to wait, so the reply to a stranger's /run-all in the client's PR was still
     * to go at a restart. pr-commands.json keeps it apart from Ignite's, and after the restart it goes to the client's
     * PR #69.
     */
    @Test
    void aReplyStillToGoAtARestartGoesToTheClientsPr(@TempDir Path dir) throws Exception {
        when(github.appComment(eq(CLIENT_REPO), eq(PR), eq("@someone"), anyString()))
            .thenThrow(new GithubClient.WriteDeferred("GitHub asked to wait 60 s"))
            .thenReturn(new GithubClient.PostedComment(90L, null));
        clientComment(1L, "/run-all", "Someone");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);

        JsonNode saved = mapper.readTree(file.toFile());
        assertThat(saved.get("outbox")).isEmpty();
        assertThat(saved.get("clientOutbox")).singleElement().satisfies(r -> {
            assertThat(r.get("repo").asString()).isEqualTo(CLIENT_REPO);
            assertThat(r.get("pr").asInt()).isEqualTo(PR);
        });

        PrCommands restarted = commands();
        restarted.loadFrom(file);
        restarted.poll();

        verify(github, times(2)).appComment(eq(CLIENT_REPO), eq(PR), eq("@someone"), endsWith("queues the Python thin"
            + " client suite under your ci2 account."));
        verify(github, never()).appComment(anyInt(), any(), anyString());
    }

    @Test
    void aPollReadsTheClientsCommentsWithOneMoreCall() {
        commands.poll();

        verify(github, times(1)).recentIssueComments(anyString());
        verify(github, times(1)).recentIssueComments(eq(CLIENT_REPO), anyString());
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"),
            new PythonClientRuns(new PythonClientProperties(null, null, null, null), github, tc, analyzer,
                new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
                new TeamcityProperties("https://ci2.example/")));
    }

    private void clientComment(long id, String body, String login) {
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(
            new GithubClient.IssueComment(id, body, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-" + id,
                Instant.now().toString(), GhUsers.of(login))));
        commands.poll();
    }

    private void igniteComment(long id, String body, String login) {
        when(github.recentIssueComments(anyString())).thenReturn(List.of(new GithubClient.IssueComment(id, body,
            "https://github.com/apache/ignite/pull/69#issuecomment-" + id, Instant.now().toString(),
            GhUsers.of(login))));
        commands.poll();
    }

    private static TcModel.Build run(String state, String status) {
        return new TcModel.Build(RUN, status, state, "pull/69", SUITE,
            "https://ci2.example/viewLog.html?buildId=9424005", null, null, null, null, null,
            new TcModel.BuildType(SUITE, "Thin client: Python"), null, null, null, null, null, null);
    }

    private static TcModel.Build chain() {
        return new TcModel.Build(CHAIN, null, "queued", "pull/69/head", "IgniteTests24Java8_RunAll",
            "https://ci2.example/build/9424100", null, null, null, null, null, null, null, null, null, null, null,
            null);
    }

    /** One test master never failed, which failed the run, and one master fails too; incomplete since then, or 0. */
    private static AnalysisResult verdict(long incompleteSince) {
        TestVerdict blocker = new TestVerdict(502L, "test_sql.test_sql_fields_query", SUITE, RUN,
            "Thin client: Python", "o2", true, false, "not seen failing in 100 master runs; failed the only run on"
            + " this branch", "F", 1, List.of(TestVerdict.Doubt.ONE_RUN));
        TestVerdict shared = new TestVerdict(501L, "test_cache_config.test_get", SUITE, RUN, "Thin client: Python",
            "o1", false, false, "pre-existing: fails 3/100 on master", "F", 0);

        return new AnalysisResult(PR, RUN, "pull/69", System.currentTimeMillis(), List.of(blocker), List.of(),
            List.of(shared), List.of(), List.of(), 1, 0, false, 0, false, 0, 0, 0, 0, 0, List.of(), List.of(),
            List.of(), incompleteSince);
    }
}
