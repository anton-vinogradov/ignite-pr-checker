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
import com.github.igniteprchecker.analysis.ClientVerdicts;
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
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.style.StyleFixService;
import com.github.igniteprchecker.tc.PinnedRun;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import com.github.igniteprchecker.web.AuthInterceptor;
import com.github.igniteprchecker.web.ClientPrsController;
import com.github.igniteprchecker.web.PrHeads;
import com.github.igniteprchecker.web.YourPrs;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The author of PR #69 of apache/ignite-python-thin-client, "IGNITE-29117 Fix test to forcefully use H2 engine",
 * presses Run on the checker's page of the PR instead of posting {@code /run-all}. As with a RunAll queued on the page
 * of an Ignite PR, the author's options act on the run: the auto re-run, the JIRA visa, and the verdict comment of
 * "Post my runs' verdicts in the PR". No comment tells how the run goes: the page shows that.
 */
class ClientPageRunTest {
    private static final String CLIENT_REPO = "apache/ignite-python-thin-client";

    private static final int PR = 69;

    private static final long RUN = 9424794L;

    private static final long RERUN = 9424810L;

    private static final long SECOND_RUN = 9424900L;

    private static final long COMMAND_RUN = 9424005L;

    private static final String SUITE = "IgniteTests24Java8_WiPThinClientPython";

    private static final String IGNITE = "1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c";

    private static final String CLIENT = "a1b2c3d4e5f60718a1b2c3d4e5f60718a1b2c3d4";

    private static final String PAGE = "https://checker.example/?repo=python&pr=69";

    private static final String ISSUE = "IGNITE-29117";

    private static final PinnedRun PINNED = new PinnedRun(SUITE, "pull/69", List.of(
        new PinnedRun.Pin("335", "refs/heads/master", IGNITE), new PinnedRun.Pin("314", "refs/pull/69/head", CLIENT)),
        Map.of("OVERRIDDEN_BRANCH", CLIENT));

    private static final String PAGE_RUN = "Ignite PR Checker: Run on the checker's page for https://github.com/"
        + CLIENT_REPO + "/pull/69";

    private static final String AUTO_RERUN = "Ignite PR Checker: auto re-run of build 9424794 for"
        + " https://github.com/apache/ignite-python-thin-client/pull/69, on the same commits";

    private final GithubClient github = AppWriting.github();

    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class),
        jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class),
        new SettingsJournal(mapper));

    private final PythonClientRuns python = new PythonClientRuns(new PythonClientProperties(null, null, null, null),
        github, tc, analyzer, new AnalysisProperties(null, "IgniteTests24Java8_RunAll", null, null, null, null, null),
        new TeamcityProperties("https://ci2.example/"));

    private final PrCommands commands = commands();

    private final ClientPrsController page = page(commands);

    @BeforeEach
    void setUp() {
        when(github.prHeadSha(CLIENT_REPO, PR)).thenReturn(CLIENT);
        when(github.branchHeadSha("apache/ignite", "master")).thenReturn(IGNITE);
        when(github.pullState(CLIENT_REPO, PR)).thenReturn(Optional.of(new GithubClient.PullState(ISSUE
            + " Fix test to forcefully use H2 engine", true, false)));
        when(tc.vcsRootInstances("tc", SUITE)).thenReturn(Optional.of(Map.of("GitHubApacheIgnite", "335",
            "GitHubApacheIgnitePythonThinClient", "314")));
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenAnswer(inv -> {
            String why = inv.getArgument(3, String.class);

            return build(why.startsWith("Ignite PR Checker: auto re-run") ? RERUN
                : why.startsWith("Ignite PR Checker: /run-all") ? COMMAND_RUN : RUN, "queued", null);
        });
        when(github.appComment(eq(CLIENT_REPO), eq(PR), any(), anyString())).thenAnswer(inv ->
            new GithubClient.PostedComment(inv.getArgument(3, String.class).contains("the verdict of") ? 89L : 88L,
                "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-88"));
        when(jira.myself("jira-pat")).thenReturn(Optional.of("Author"));
        when(jira.addCommentWithId(eq("jira-pat"), eq(ISSUE), anyString()))
            .thenReturn(new JiraClient.PostedComment("18000001", "https://issues.example/c/18000001"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN)).thenReturn(verdict(RUN, true));
    }

    @Test
    void theVerdictCommentOfTheOptionMentionsTheStarterAndNoCommentTellsTheRun() {
        linkGithub();
        options(null, null, true);

        assertThat(page.trigger(PR, false, false, false, "tc", "author").getStatusCode().value()).isEqualTo(200);
        when(tc.getBuildState("tc", RUN)).thenReturn(build(RUN, "running", null));
        commands.updateEtas();

        verify(tc).queuePinned("tc", PINNED, false, PAGE_RUN);
        verify(github, never()).appComment(anyString(), anyInt(), any(), anyString());

        finished(RUN, "FAILURE");
        commands.updateEtas();
        commands.updateEtas();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), body.capture());
        assertThat(body.getValue())
            .startsWith("@Author the verdict of the run you queued on the checker's page: **Thin client: Python**"
                + " [build 9424794](https://ci2.example/build/9424794) ran this PR's head [`a1b2c3d`](https://github.com/"
                + CLIENT_REPO + "/pull/69/commits/" + CLIENT + ") against Ignite master at [`1f2e3d4`]"
                + "(https://github.com/apache/ignite/commit/" + IGNITE + ").")
            .contains("❌ **1 blocker:**\n- `test_sql.test_sql_fields_query` — not seen failing in 100 master runs")
            .contains("**1 failing test is not blamed on this PR.** [The checker's page](" + PAGE + ") shows it with"
                + " its reason.");
        verify(github, never()).appEdit(anyString(), anyInt(), any(), anyLong(), anyString());
        verify(github, never()).appDelete(anyString(), anyInt(), any(), anyLong());
        verify(analyzer, times(1)).analyzeSuiteRun("tc", PR, "pull/69", RUN);
    }

    /** Post my runs' verdicts in the PR needs no linked GitHub account: the comment then mentions nobody. */
    @Test
    void withoutALinkedGithubAccountTheCommentMentionsNobody() {
        options(null, null, true);

        page.trigger(PR, false, false, false, "tc", "author");
        finished(RUN, "FAILURE");
        commands.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("The verdict of a run queued on the"
            + " checker's page: **Thin client: Python** [build 9424794]"));
    }

    /**
     * The author's enrollment comes from v1.28, which took the GitHub login Vladsz83 as typed, with no token to prove
     * it. Anyone could have typed that login, so the verdict comment of the author's run mentions nobody.
     */
    @Test
    void anUnprovenLoginIsNotMentioned(@TempDir Path dir) throws Exception {
        ObjectNode e = mapper.createObjectNode();
        e.put("username", "author");
        e.put("tcToken", codec.encryptString("tc"));
        e.putNull("jiraToken");
        e.putNull("ghToken");
        e.put("ghLogin", "Vladsz83");
        e.put("enabledAt", 1760000000000L);
        e.put("autoVisa", false);
        e.put("autoRerun", false);
        e.put("ghComment", true);
        e.put("commands", false);
        e.put("ghLoginProven", false);
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.putArray("enrollments").add(e);
        Path file = dir.resolve("standing-visas.json");
        mapper.writeValue(file.toFile(), snapshot);
        standing.loadFrom(file);

        page.trigger(PR, false, false, false, "tc", "author");
        finished(RUN, "FAILURE");
        commands.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("The verdict of a run queued on the"
            + " checker's page: **Thin client: Python** [build 9424794]"));
    }

    /** GitHub asked the checker to wait when the verdict comment of the author's run went out: it goes later. */
    @Test
    void aVerdictCommentGithubPutOffGoesLater() {
        linkGithub();
        options(null, null, true);
        when(github.appComment(eq(CLIENT_REPO), eq(PR), eq("author"), anyString()))
            .thenThrow(new GithubClient.WriteDeferred("GitHub asked to wait"))
            .thenReturn(new GithubClient.PostedComment(89L, "https://github.com/" + CLIENT_REPO
                + "/pull/69#issuecomment-89"));

        page.trigger(PR, false, false, false, "tc", "author");
        finished(RUN, "FAILURE");
        commands.updateEtas();
        commands.updateEtas();

        verify(github, times(2)).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("@Author the verdict of"
            + " the run you queued on the checker's page"));
        verify(analyzer, times(1)).analyzeSuiteRun("tc", PR, "pull/69", RUN);
    }

    /**
     * GitHub asked the checker to wait when the verdict comment of the author's run went out, and the author switched
     * verdict comments off before the next try. With that option off, the run leaves no comment in the PR.
     */
    @Test
    void aVerdictCommentGithubPutOffDoesNotGoOnceTheOptionIsOff() {
        linkGithub();
        options(null, null, true);
        when(github.appComment(eq(CLIENT_REPO), eq(PR), eq("author"), anyString()))
            .thenThrow(new GithubClient.WriteDeferred("GitHub asked to wait"));

        page.trigger(PR, false, false, false, "tc", "author");
        finished(RUN, "FAILURE");
        commands.updateEtas();
        options(null, null, false);
        commands.updateEtas();

        verify(github, times(1)).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), anyString());
        assertThat(commands.clientPhase(PR, RUN).phase()).isEqualTo("final");
    }

    /**
     * The author has the auto re-run and the visa on, and verdict comments off. The run failed a test master never
     * failed: the suite runs once more on the same commits, the visa follows the re-run, and the PR gets no comment.
     * Until then the page reads the verdict as settling.
     */
    @Test
    void theAutoRerunAndTheVisaActOnTheRunAndNoCommentGoesToThePr() {
        options(true, true, null);

        page.trigger(PR, false, false, false, "tc", "author");
        finished(RUN, "FAILURE");
        StandingVisas.Phase deciding = commands.clientPhase(PR, RUN);
        commands.updateEtas();
        StandingVisas.Phase rerunning = commands.clientPhase(PR, RUN);

        verify(tc).queuePinned("tc", PINNED, true, AUTO_RERUN);
        assertThat(deciding.phase()).isEqualTo("settling");
        assertThat(rerunning.phase()).isEqualTo("settling");
        assertThat(rerunning.wave()).isEqualTo(1);

        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "finished", "SUCCESS"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN, RERUN)).thenReturn(verdict(RUN, false));
        commands.updateEtas();

        ArgumentCaptor<String> visa = ArgumentCaptor.forClass(String.class);
        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), visa.capture());
        assertThat(visa.getValue()).startsWith("Ignite PR Checker verdict for [PR 69 of " + CLIENT_REPO)
            .contains("♻️ Settled after one auto re-run: [build 9424810|https://ci2.example/build/9424810]");
        verify(github, never()).appComment(anyString(), anyInt(), any(), anyString());
        assertThat(commands.clientPhase(PR, RUN).phase()).isEqualTo("final");
    }

    /**
     * The author has no option on when they press Run, so the checker keeps no TeamCity token of theirs and does not
     * look at the run, and the page reads its verdict as final. While the run goes, the author switches on the visa,
     * the auto re-run and verdict comments: they act on the run, as on a RunAll queued on the page.
     */
    @Test
    void optionsSwitchedOnWhileTheRunGoesActOnIt(@TempDir Path dir) throws Exception {
        page.trigger(PR, false, false, false, "tc", "author");
        commands.updateEtas();
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);

        verify(tc, never()).getBuildState(anyString(), anyLong());
        assertThat(commands.clientPhase(PR, RUN).phase()).isEqualTo("final");

        options(true, true, true);
        finished(RUN, "FAILURE");
        commands.updateEtas();

        verify(tc).queuePinned("tc", PINNED, true, AUTO_RERUN);

        when(tc.getBuildState("tc", RERUN)).thenReturn(build(RERUN, "finished", "SUCCESS"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN, RERUN)).thenReturn(verdict(RUN, false));
        commands.updateEtas();

        verify(jira).addCommentWithId(eq("jira-pat"), eq(ISSUE), startsWith("Ignite PR Checker verdict for [PR 69 of "
            + CLIENT_REPO));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("The verdict of a run queued on the"
            + " checker's page: **Thin client: Python** [build 9424794]"));
        assertThat(mapper.readTree(file.toFile()).get("pageRuns").size()).as("kept while it waits").isEqualTo(1);
    }

    /**
     * The author switches the auto re-run, the visa and verdict comments on an hour after the run they queued on the
     * page with no option on finished. An option does not reach back: none of them acts on the run.
     */
    @Test
    void optionsSwitchedOnAfterTheRunFinishedDoNotActOnIt() {
        page.trigger(PR, false, false, false, "tc", "author");
        finished(RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", RUN))
            .thenReturn(verdict(RUN, true, Instant.now().minusSeconds(3600)));
        options(true, true, true);
        commands.updateEtas();
        commands.updateEtas();

        verify(tc, never()).queuePinned("tc", PINNED, true, AUTO_RERUN);
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
        verify(github, never()).appComment(anyString(), anyInt(), any(), anyString());
        assertThat(commands.clientPhase(PR, RUN).phase()).isEqualTo("final");
    }

    /**
     * The author pressed Run with no option on and switched none on for a week: the checker no longer waits for one,
     * and pr-commands.json no longer keeps the run. An option switched on after that does not act on it.
     */
    @Test
    void aRunNoOptionWentOnForInAWeekIsNoLongerFollowed(@TempDir Path dir) throws Exception {
        long[] now = {System.currentTimeMillis()};
        commands.clock(() -> now[0]);
        page.trigger(PR, false, false, false, "tc", "author");
        Path file = dir.resolve("pr-commands.json");
        now[0] += Duration.ofDays(7).toMillis() - 60_000;
        commands.updateEtas();
        commands.saveTo(file);
        int waiting = mapper.readTree(file.toFile()).get("pageRuns").size();

        now[0] += 60_000;
        commands.updateEtas();
        commands.saveTo(file);
        options(null, true, true);
        finished(RUN, "FAILURE");
        commands.updateEtas();

        assertThat(waiting).isEqualTo(1);
        assertThat(mapper.readTree(file.toFile()).get("pageRuns").isEmpty()).isTrue();
        verify(tc, never()).getBuildState(anyString(), anyLong());
        verify(github, never()).appComment(anyString(), anyInt(), any(), anyString());
    }

    /**
     * The author has only PR commands on: no option of theirs acts on a finished run, so the run gets no verdict, no
     * visa and no re-run, and the checker does not judge it. The page reads its verdict final at once.
     */
    @Test
    void aRunNoOptionActsOnIsNotJudged() {
        linkGithub();

        page.trigger(PR, false, false, false, "tc", "author");
        StandingVisas.Phase queued = commands.clientPhase(PR, RUN);
        finished(RUN, "FAILURE");
        commands.updateEtas();
        commands.updateEtas();

        assertThat(queued.phase()).isEqualTo("final");
        verify(analyzer, never()).analyzeSuiteRun(anyString(), anyInt(), anyString(), anyLong());
        verify(tc, times(1)).getBuildState("tc", RUN);
        verify(github, never()).appComment(anyString(), anyInt(), any(), anyString());
        verify(jira, never()).addCommentWithId(anyString(), anyString(), anyString());
    }

    /**
     * The author's {@code /run-all} still runs when they press Run on the page, which asks to replace it: the page's
     * run cancels it, and the story of the {@code /run-all} ends. Its verdict never comes.
     */
    @Test
    void runOnThePageReplacesTheRunOfACommandAndEndsItsStory() {
        linkGithub();
        options(null, null, true);
        clientComment(1L, "/run-all");
        when(tc.cancelOwnBuilds(eq("tc"), eq("pull/69"), eq("author"), any())).thenReturn(1);

        page.trigger(PR, false, true, false, "tc", "author");
        when(tc.getBuildState("tc", COMMAND_RUN)).thenReturn(build(COMMAND_RUN, "finished", "UNKNOWN"));
        finished(RUN, "FAILURE");
        commands.updateEtas();

        verify(github).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), endsWith("\n🛑 _Superseded by a newer"
            + " run queued on [the checker's page](" + PAGE + ")._"));
        verify(github, never()).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("your `/run-all`"));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("@Author the verdict of the run"
            + " you queued on the checker's page: **Thin client: Python** [build 9424794]"));
    }

    /**
     * The author's {@code /run-all} still runs when they press Run on the page without replacing it: the story of the
     * {@code /run-all} goes on, and its verdict comes.
     */
    @Test
    void runWithoutReplaceLeavesTheStoryOfTheCommandAlone() {
        linkGithub();
        options(null, null, true);
        clientComment(1L, "/run-all");

        page.trigger(PR, false, false, false, "tc", "author");
        finished(COMMAND_RUN, "FAILURE");
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", COMMAND_RUN)).thenReturn(verdict(COMMAND_RUN, true));
        commands.updateEtas();

        verify(github, never()).appEdit(eq(CLIENT_REPO), eq(PR), eq("author"), eq(88L), contains("Superseded"));
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("@Author the verdict of your"
            + " `/run-all`: **Thin client: Python** [build 9424005]"));
    }

    /** {@code /top} moves the run of the author's own {@code /run-all}: a run queued on the page is not one. */
    @Test
    void topDoesNotMoveARunQueuedOnThePage() {
        linkGithub();
        options(null, null, true);
        page.trigger(PR, false, false, false, "tc", "author");
        when(tc.getBuildState("tc", RUN)).thenReturn(build(RUN, "queued", null));

        clientComment(2L, "/top");

        verify(tc, never()).moveToQueueTop(anyString(), anyLong());
        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), contains("⬆️ _Nothing moved: `/top` moves the"
            + " run that your own `/run-all` started on this PR, and none is in progress._"));
    }

    /**
     * The service restarted while the run went: pr-commands.json keeps the run, apart from the runs of commands, and
     * the checker follows it on. A version before this one reads the runs of commands only, and does not tell this
     * run in a comment of its own.
     */
    @Test
    void aRunIsFollowedAfterARestartAndKeptApartFromTheRunsOfCommands(@TempDir Path dir) throws Exception {
        linkGithub();
        options(null, null, true);
        page.trigger(PR, false, false, false, "tc", "author");
        Path file = dir.resolve("pr-commands.json");
        commands.saveTo(file);

        JsonNode saved = mapper.readTree(file.toFile());
        assertThat(saved.get("clientRuns").isEmpty()).isTrue();
        assertThat(saved.get("pageRuns").get(0).get("buildId").asLong()).isEqualTo(RUN);

        PrCommands restarted = commands();
        restarted.loadFrom(file);
        finished(RUN, "FAILURE");
        restarted.updateEtas();

        verify(github).appComment(eq(CLIENT_REPO), eq(PR), eq("author"), startsWith("@Author the verdict of the run"
            + " you queued on the checker's page"));
    }

    /**
     * The checker queued the auto re-run of the author's {@code /run-all} just as the author pressed Run on the page.
     * The page's run cancels the author's runs of the PR after the re-run went into the queue, so the re-run is among
     * the builds it cancels.
     */
    @Test
    void aRerunQueuedAsRunIsPressedIsCancelledByIt() throws Exception {
        linkGithub();
        options(null, true, null);
        clientComment(1L, "/run-all");
        when(tc.getBuildState("tc", COMMAND_RUN)).thenReturn(build(COMMAND_RUN, "finished", "FAILURE"));
        when(analyzer.analyzeSuiteRun("tc", PR, "pull/69", COMMAND_RUN)).thenReturn(verdict(COMMAND_RUN, true));
        CountDownLatch rerunAsked = new CountDownLatch(1);
        CountDownLatch runsCancelled = new CountDownLatch(1);
        AtomicBoolean rerunInQueue = new AtomicBoolean();
        AtomicBoolean cancelSawRerun = new AtomicBoolean();
        when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenAnswer(inv -> {
            if (!inv.getArgument(3, String.class).startsWith("Ignite PR Checker: auto re-run"))
                return build(SECOND_RUN, "queued", null);

            rerunAsked.countDown();
            runsCancelled.await(1, TimeUnit.SECONDS);
            rerunInQueue.set(true);

            return build(RERUN, "queued", null);
        });
        when(tc.cancelOwnBuilds(eq("tc"), eq("pull/69"), eq("author"), any())).thenAnswer(inv -> {
            cancelSawRerun.set(rerunInQueue.get());
            runsCancelled.countDown();

            return rerunInQueue.get() ? 1 : 0;
        });
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                if (rerunAsked.await(5, TimeUnit.SECONDS))
                    page.trigger(PR, false, true, false, "tc", "author");
            }
            catch (Throwable e) {
                failed.set(e);
            }
        });
        run.setDaemon(true);
        run.start();

        commands.updateEtas();
        run.join(10_000);

        assertThat(failed.get()).isNull();
        assertThat(cancelSawRerun).isTrue();
    }

    private PrCommands commands() {
        return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
            mock(SuiteBaseline.class), "https://checker.example", new TeamcityProperties("https://ci2.example/"),
            python);
    }

    private ClientPrsController page(PrCommands c) {
        return new ClientPrsController(mock(ClientVerdicts.class), python, tc, github, new PrHeads(github),
            new YourPrs(github, standing), standing, c, mock(AuthInterceptor.class));
    }

    /** The author links their GitHub account Author, which switches PR commands on. */
    private void linkGithub() {
        standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
        standing.firstCommand("author");
    }

    /** Switches the author's visa, auto re-run and verdict comments as given; null leaves one as it is. */
    private void options(Boolean visa, Boolean rerun, Boolean comment) {
        assertThat(standing.change("author", "tc", Boolean.TRUE.equals(visa) ? "jira-pat" : null, null,
            new StandingVisas.OptionChange(visa, rerun, comment, null, null))).isEmpty();
    }

    private void clientComment(long id, String body) {
        when(github.recentIssueComments(eq(CLIENT_REPO), anyString())).thenReturn(List.of(
            new GithubClient.IssueComment(id, body, "https://github.com/" + CLIENT_REPO + "/pull/69#issuecomment-" + id,
                Instant.now().toString(), GhUsers.of("Author"))));
        commands.poll();
    }

    private void finished(long id, String status) {
        when(tc.getBuildState("tc", id)).thenReturn(build(id, "finished", status));
    }

    private static TcModel.Build build(long id, String state, String status) {
        return new TcModel.Build(id, status, state, "pull/69", SUITE, "https://ci2.example/viewLog.html?buildId=" + id,
            null, null, null, null, null, new TcModel.BuildType(SUITE, "Thin client: Python"), null, null, null, null,
            null, null);
    }

    /**
     * Build {@code buildId} as judged a minute from now, after the options went on: a test master never failed, a
     * blocker when {@code blocked}, else flaky, and a test master fails too.
     */
    private static AnalysisResult verdict(long buildId, boolean blocked) {
        return verdict(buildId, blocked, Instant.now().plusSeconds(60));
    }

    /** Build {@code buildId} as {@link #verdict(long, boolean)} judges it, finished at {@code finished}. */
    private static AnalysisResult verdict(long buildId, boolean blocked, Instant finished) {
        TestVerdict sql = new TestVerdict(502L, "test_sql.test_sql_fields_query", SUITE, buildId,
            "Thin client: Python", "o2", blocked, false, blocked ? "not seen failing in 100 master runs; failed the"
            + " only run on this branch" : "flaky on branch: passed on the re-run", "F", 1);
        TestVerdict shared = new TestVerdict(501L, "test_cache_config.test_get", SUITE, buildId, "Thin client: Python",
            "o1", false, false, "pre-existing: fails 3/100 on master", "F", 0);

        return new AnalysisResult(PR, buildId, "pull/69", System.currentTimeMillis(),
            blocked ? List.of(sql) : List.of(), List.of(), blocked ? List.of(shared) : List.of(shared, sql), List.of(),
            List.of(), 1, 0, false, 0, false, 0, 0, 0, finished.getEpochSecond(), 0, List.of(), List.of(), List.of(), 0,
            IGNITE + "+" + CLIENT, 0);
    }
}
