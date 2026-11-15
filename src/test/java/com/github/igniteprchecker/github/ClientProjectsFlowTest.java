package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.AnalysisProperties;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ClientProjectsFlowTest {
    @TempDir
    Path temp;

    /**
     * Given PR 69 commands in each client repository and a failing first run, when the stories resume after restart,
     * then each reruns its own commits and posts its own final verdict.
     */
    @Test
    void followsSameNumberPrsInEveryProjectAcrossRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.commands.poll();
            assertThat(f.queued).hasSize(3);
            Path state = temp.resolve("pr-commands.json");
            f.commands.saveTo(state);
            JsonNode saved = f.mapper.readTree(state);
            assertThat(saved.path("clientRuns").size()).isEqualTo(1);
            assertThat(saved.path("otherClientRuns").size()).isEqualTo(2);

            PrCommands resumed = f.commands();
            resumed.loadFrom(state);
            resumed.updateEtas();
            assertThat(f.queued).hasSize(6);
            resumed.updateEtas();

            for (ClientSpec spec : f.projects.all()) {
                List<PinnedRun> runs = f.queued.stream().filter(p -> p.suite().equals(spec.suite())).toList();
                assertThat(runs).hasSize(2);
                assertThat(runs.get(1)).isEqualTo(runs.get(0));
                List<Posted> finals = f.posted.stream().filter(p -> p.repo().equals(spec.repo())
                    && p.body().toLowerCase(Locale.ROOT).contains("the verdict of")).toList();
                assertThat(finals).singleElement().satisfies(p -> {
                    assertThat(p.body()).contains(spec.repo() + "/pull/69", "/" + spec.key() + "?pr=69",
                        f.head(spec).substring(0, 7));
                    for (ClientSpec other : f.projects.all())
                        if (!other.equals(spec))
                            assertThat(p.body()).doesNotContain(other.repo());
                });
                assertThat(resumed.clientPhase(spec, 69, f.buildId(spec)).phase()).isEqualTo("final");
            }
        }
    }

    /**
     * Given a Python snapshot without the new repository fields, when it is loaded and saved,
     * then its story and poll window still belong to Python.
     */
    @Test
    void migratesPythonStateWithoutReassigningItToAnotherProject() throws Exception {
        try (Fixture f = new Fixture()) {
            for (ClientSpec spec : f.projects.all())
                if (!spec.key().equals("python"))
                    when(f.github.recentIssueComments(eq(spec.repo()), anyString())).thenReturn(List.of());
            f.commands.poll();
            Path old = temp.resolve("old.json");
            f.commands.saveTo(old);
            ObjectNode snapshot = (ObjectNode) f.mapper.readTree(old);
            snapshot.remove(List.of("clientSinceByRepo", "otherClientRuns", "otherClientOutbox"));
            snapshot.put("clientSinceMs", 1_700_000_000_000L);
            f.mapper.writeValue(old.toFile(), snapshot);

            PrCommands resumed = f.commands();
            resumed.loadFrom(old);
            Path migrated = temp.resolve("migrated.json");
            resumed.saveTo(migrated);

            JsonNode result = f.mapper.readTree(migrated);
            assertThat(result.path("clientRuns").size()).isEqualTo(1);
            assertThat(result.path("clientRuns").get(0).path("repo").asText())
                .isEqualTo("apache/ignite-python-thin-client");
            assertThat(result.path("otherClientRuns").size()).isZero();
            assertThat(result.path("clientSinceByRepo").path("apache/ignite-python-thin-client").asLong())
                .isEqualTo(1_700_000_000_000L);
        }
    }

    /**
     * Given different titles for PR 69 in each client, when the page requests those titles,
     * then each response contains its repository's title.
     */
    @Test
    void keepsTitlesSeparateForSameNumberPrs() throws Exception {
        try (Fixture f = new Fixture()) {
            ClientPrsController page = f.page();
            for (ClientSpec spec : f.projects.all()) {
                when(f.github.openPrs(spec.repo())).thenReturn(List.of(new PrSummary(69, spec.label() + " title",
                    null, null, null, null)));
                var response = page.title(spec.key(), 69);
                assertThat(((Map<?, ?>) response.getBody()).get("title")).isEqualTo(spec.label() + " title");
            }
        }
    }

    /**
     * Given a missing or unknown project key, when a launch is requested,
     * then validation returns 400 before GitHub reads or TeamCity mutations.
     */
    @Test
    void rejectsMissingAndUnknownProjectBeforeLaunch() throws Exception {
        try (Fixture f = new Fixture()) {
            var mvc = MockMvcBuilders.standaloneSetup(f.page()).build();
            var missing = post("/api/client/trigger").param("pr", "69")
                .requestAttr(AuthInterceptor.TOKEN_ATTR, "tc").requestAttr(AuthInterceptor.USER_ATTR, "author");
            mvc.perform(missing).andExpect(status().isBadRequest());
            for (String key : List.of("", "ruby", "ignite", "__proto__"))
                mvc.perform(post("/api/client/trigger").param("repo", key).param("pr", "69")
                        .requestAttr(AuthInterceptor.TOKEN_ATTR, "tc")
                        .requestAttr(AuthInterceptor.USER_ATTR, "author"))
                    .andExpect(status().isBadRequest());

            verify(f.github, never()).prHeadSha(anyString(), anyInt());
            verify(f.tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
            verify(f.tc, never()).cancelOwnBuilds(anyString(), anyString(), anyString(), any());
        }
    }

    private record Posted(String repo, String body) {
    }

    /**
     * Supplies enrolled command authors and three remote repositories. GitHub and TeamCity are external doubles;
     * the analyzer supplies failed and passed verdicts to exercise the story's rerun and completion stages.
     */
    private static final class Fixture implements AutoCloseable {
        final ThinClients projects = ThinClients.defaults();
        final GithubClient github = AppWriting.github();
        final TcClient tc = mock(TcClient.class);
        final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        final ObjectMapper mapper = Jackson2Defaults.mapper();
        final AnalysisProperties analysis = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        final TeamcityProperties teamcity = new TeamcityProperties("https://ci2.example/");
        final ExecutorService early = Executors.newSingleThreadExecutor();
        final ExecutorService settle = Executors.newSingleThreadExecutor();
        final StandingVisas standing = new StandingVisas(mapper,
            new SessionCodec(new SessionProperties(false, "test-secret"), mapper), tc, github,
            mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class),
            mock(Warmer.class), mock(PendingCommits.class), early, settle, new SettingsJournal(mapper));
        final ClientRuns engine = new ClientRuns(github, tc, analyzer, analysis, teamcity);
        final List<PinnedRun> queued = new ArrayList<>();
        final List<Posted> posted = new ArrayList<>();
        final AtomicLong commentIds = new AtomicLong(10000);
        final PrCommands commands;

        Fixture() {
            standing.linkGhLogin("author", "tc", GhUsers.of("Author"));
            standing.firstCommand("author");
            standing.change("author", "tc", null, null,
                new StandingVisas.OptionChange(false, true, true, false, true));
            when(github.branchHeadSha("apache/ignite", "master")).thenReturn("d".repeat(40));
            for (ClientSpec spec : projects.all()) {
                long comment = 1 + projects.all().indexOf(spec);
                when(github.recentIssueComments(eq(spec.repo()), anyString())).thenReturn(List.of(
                    new GithubClient.IssueComment(comment, "/run-all", "https://github.com/" + spec.repo()
                        + "/pull/69#issuecomment-" + comment, Instant.now().toString(), GhUsers.of("Author"))));
                when(github.pullState(spec.repo(), 69))
                    .thenReturn(Optional.of(new GithubClient.PullState("IGNITE-29117 Test", true, false)));
                when(github.prHeadSha(spec.repo(), 69)).thenReturn(head(spec));
                when(tc.vcsRootInstances("tc", spec.suite())).thenReturn(Optional.of(Map.of(
                    spec.igniteVcsRoot(), "335", spec.clientVcsRoot(), String.valueOf(314 + comment))));
                when(tc.getBuildState("tc", buildId(spec))).thenReturn(build(spec, buildId(spec), "FAILURE"));
                when(tc.getBuildState("tc", buildId(spec) + 100)).thenReturn(build(spec, buildId(spec) + 100, "SUCCESS"));
                when(analyzer.analyzeSuiteRun("tc", 69, spec.branch(69), buildId(spec)))
                    .thenReturn(verdict(spec, true));
                when(analyzer.analyzeSuiteRun("tc", 69, spec.branch(69), buildId(spec), buildId(spec) + 100))
                    .thenReturn(verdict(spec, false));
                when(github.appComment(eq(spec.repo()), eq(69), any(), anyString())).thenAnswer(inv -> {
                    posted.add(new Posted(spec.repo(), inv.getArgument(3)));
                    return new GithubClient.PostedComment(commentIds.incrementAndGet(),
                        "https://github.com/" + spec.repo() + "/pull/69#c" + commentIds.get());
                });
            }
            when(tc.queuePinned(eq("tc"), any(), anyBoolean(), anyString())).thenAnswer(inv -> {
                PinnedRun run = inv.getArgument(1);
                queued.add(run);
                ClientSpec spec = projects.all().stream().filter(c -> c.suite().equals(run.suite()))
                    .findFirst().orElseThrow();
                boolean rerun = inv.getArgument(3, String.class).contains("auto re-run");
                return build(spec, buildId(spec) + (rerun ? 100 : 0), null);
            });
            commands = commands();
        }

        PrCommands commands() {
            return new PrCommands(mapper, github, standing, tc, mock(RerunTracker.class), mock(StyleFixService.class),
                mock(SuiteBaseline.class), "https://checker.example", teamcity, engine);
        }

        ClientPrsController page() {
            return new ClientPrsController(new ClientVerdicts(tc, analyzer, github, analysis), engine, tc, github,
                mock(PrHeads.class), new YourPrs(github, standing), standing, commands, mock(AuthInterceptor.class),
                projects);
        }

        long buildId(ClientSpec spec) {
            return 1000 + projects.all().indexOf(spec);
        }

        String head(ClientSpec spec) {
            return String.valueOf((char) ('a' + projects.all().indexOf(spec))).repeat(40);
        }

        TcModel.Build build(ClientSpec spec, long id, String status) {
            return new TcModel.Build(id, status, status == null ? "queued" : "finished", spec.branch(69),
                spec.suite(), "https://ci2.example/build/" + id, null, null, null, null, null,
                new TcModel.BuildType(spec.suite(), spec.suiteLabel()), null, null, null, null, null, null);
        }

        AnalysisResult verdict(ClientSpec spec, boolean blocked) {
            long id = buildId(spec);
            TestVerdict failure = new TestVerdict(500L, "ClientTest.failure", spec.suite(), id, spec.suiteLabel(),
                "o1", true, false, "not seen failing in 100 master runs", "F", 1);
            return new AnalysisResult(69, id, spec.branch(69), System.currentTimeMillis(),
                blocked ? List.of(failure) : List.of(), List.of(), List.of(), List.of(), List.of(), 1, 0, false,
                0, false, 0, 0, 0, Instant.now().plusSeconds(60).getEpochSecond(), 0, List.of(), List.of(), List.of(),
                0, "d".repeat(40) + "+" + head(spec), 0);
        }

        @Override public void close() {
            early.shutdownNow();
            settle.shutdownNow();
        }
    }
}
