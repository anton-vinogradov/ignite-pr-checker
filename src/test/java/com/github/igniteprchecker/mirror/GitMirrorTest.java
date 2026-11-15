package com.github.igniteprchecker.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.SuiteBaseline;
import com.github.igniteprchecker.config.AnalysisProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.IgnitePrRuns;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.web.PrHeads;
import com.github.igniteprchecker.web.TriggerController;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitMirrorTest {
    @TempDir
    Path temp;

    private Path work;
    private Path heads;
    private Path prs;
    private String first;

    @BeforeEach
    void createPublicSources() throws Exception {
        work = temp.resolve("source");
        heads = temp.resolve("gitbox.git");
        prs = temp.resolve("github.git");
        git(null, "init", "--initial-branch=master", work.toString());
        git(null, "init", "--bare", heads.toString());
        git(null, "init", "--bare", prs.toString());
        first = commit("first");
        git(work, "tag", "release-1");
        git(work, "push", heads.toString(), "master", "refs/tags/release-1");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");
    }

    /** Bootstrap copies normal branches and tags; an unrequested GitHub PR remains absent. */
    @Test
    void bootstrapCopiesOnlyOfficialBranchesAndTags() throws Exception {
        String prHead = commit("PR only");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");
        GitMirror mirror = mirror(true);

        mirror.sync();

        Path repo = mirror.repository("ignite");
        assertThat(git(repo, "rev-parse", "refs/heads/master")).isEqualTo(first);
        assertThat(git(repo, "rev-parse", "refs/tags/release-1")).isEqualTo(first);
        assertThat(git(repo, "for-each-ref", "--format=%(refname)", "refs/pull/")).isEmpty();
        assertThat(gitResult(repo, "cat-file", "-e", prHead + "^{commit}").exit()).isNotZero();
        assertThat(mirror.status()).singleElement().satisfies(s -> {
            assertThat(s.ready()).isTrue();
            assertThat(s.syncing()).isFalse();
            assertThat(s.lastSuccessAt()).isNotNull();
            assertThat(s.error()).isNull();
        });
    }

    /** Selecting one PR fetches its commit and ref, leaving another PR's unique commit absent. */
    @Test
    void fetchesOnlyTheRequestedPr() throws Exception {
        String selected = commit("selected PR");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");
        String other = commit("unrequested PR");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13834/head");
        GitMirror mirror = mirror(true);
        mirror.sync();

        mirror.ensurePr("ignite", 13786, selected);

        Path repo = mirror.repository("ignite");
        assertThat(git(repo, "for-each-ref", "--format=%(refname)", "refs/pull/"))
            .isEqualTo("refs/pull/13786/head");
        assertThat(git(repo, "rev-parse", "refs/keep/" + selected)).isEqualTo(selected);
        assertThat(gitResult(repo, "cat-file", "-e", other + "^{commit}").exit()).isNotZero();
    }

    /** A closed PR ref retained by GitBox is outside the normal heads/tags bootstrap. */
    @Test
    void doesNotFetchAnUnrequestedPrRetainedByGitBox() throws Exception {
        String closed = commit("closed PR retained by GitBox");
        git(work, "push", heads.toString(), "HEAD:refs/pull/11503/head");
        GitMirror mirror = mirror(true);

        mirror.sync();

        Path repo = mirror.repository("ignite");
        assertThat(git(repo, "for-each-ref", "--format=%(refname)", "refs/pull/11503/head")).isEmpty();
        assertThat(gitResult(repo, "cat-file", "-e", closed + "^{commit}").exit()).isNotZero();
    }

    /** Background updates leave a selected PR at its last requested head until another launch asks for it. */
    @Test
    void backgroundSyncDoesNotAdvancePreviouslyRequestedPr() throws Exception {
        GitMirror mirror = mirror(true);
        mirror.sync();
        mirror.ensurePr("ignite", 13786, first);
        String next = commit("PR changed without a launch");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");

        mirror.sync();

        Path repo = mirror.repository("ignite");
        assertThat(git(repo, "rev-parse", "refs/pull/13786/head")).isEqualTo(first);
        assertThat(gitResult(repo, "cat-file", "-e", next + "^{commit}").exit()).isNotZero();
    }

    /** GitHub availability cannot prevent normal TeamCity branch jobs from using the GitBox bootstrap. */
    @Test
    void bootstrapIsReadyWhileGithubIsOffline() throws Exception {
        Files.move(prs, temp.resolve("offline-github.git"));
        GitMirror mirror = mirror(true);

        mirror.sync();

        assertThat(git(mirror.repository("ignite"), "rev-parse", "refs/heads/master")).isEqualTo(first);
        assertThat(mirror.status().get(0).ready()).isTrue();
        assertThat(mirror.status().get(0).error()).isNull();
    }

    /** A requested PR fetch failure leaves the user's existing RunAll untouched and queues no replacement. */
    @Test
    void githubLaunchFailureDoesNotCancelOrQueueBuilds() throws Exception {
        GitMirror mirror = mirror(true);
        mirror.sync();
        Files.move(prs, temp.resolve("offline-github.git"));
        TcClient tc = mock(TcClient.class);
        GithubClient github = mock(GithubClient.class);
        when(github.prHeadSha(13786)).thenReturn(first);
        AnalysisProperties cfg = new AnalysisProperties(null, "RunAll", null, null, null, null, null);
        TriggerController launch = new TriggerController(tc, mock(BlockerAnalyzer.class), mock(RerunTracker.class),
            mock(PrHeads.class), mock(SuiteBaseline.class), cfg, new IgnitePrRuns(github, tc, mirror, cfg));

        assertThatThrownBy(() -> launch.trigger(13786, false, true, true, "token", "author"))
            .isInstanceOf(GitMirror.NotReadyException.class).hasMessageContaining("command failed");

        verify(tc, never()).cancelOwnBuilds(anyString(), anyInt(), anyString(), any());
        verify(tc, never()).queuePinned(anyString(), any(), anyBoolean(), anyString());
    }

    /** A force push and upstream deletion cannot make an already selected commit unreachable. */
    @Test
    void keepsQueuedShaAndDoesNotPruneDeletedPrRefs() throws Exception {
        String original = commit("PR before rewrite");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");
        GitMirror mirror = mirror(true);
        mirror.sync();
        mirror.ensurePr("ignite", 13786, original);
        Path repo = mirror.repository("ignite");
        git(work, "checkout", "--orphan", "rewritten");
        String next = commit("next");
        git(work, "push", "--force", prs.toString(), "HEAD:refs/pull/13786/head");
        mirror.ensurePr("ignite", 13786, next);
        git(prs, "update-ref", "-d", "refs/pull/13786/head");

        mirror.sync();

        assertThat(git(repo, "rev-parse", "refs/keep/" + original)).isEqualTo(original);
        assertThat(git(repo, "rev-parse", "refs/keep/" + next)).isEqualTo(next);
        assertThat(git(repo, "rev-parse", "refs/pull/13786/head")).isEqualTo(next);
        assertThat(git(repo, "config", "gc.auto")).isEqualTo("0");
        assertThat(git(repo, "config", "maintenance.auto")).isEqualTo("false");
    }

    /** A GitHub head read before a concurrent push is refused, never silently replaced by the new head. */
    @Test
    void rejectsHeadMismatchWithoutCreatingKeepRef() throws Exception {
        String next = commit("new head");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");
        GitMirror mirror = mirror(true);
        mirror.sync();

        assertThatThrownBy(() -> mirror.ensurePr("ignite", 13786, first))
            .isInstanceOf(GitMirror.NotReadyException.class).hasMessageContaining("expected " + first);

        assertThat(git(mirror.repository("ignite"), "for-each-ref", "--format=%(refname)", "refs/keep/"))
            .isEmpty();
        assertThat(mirror.status().get(0).error()).contains(next);
    }

    /** A failed refresh leaves the previous complete repository available to Git readers. */
    @Test
    void servesPreviousSyncWhenUpstreamIsUnavailable() throws Exception {
        GitMirror mirror = mirror(true);
        mirror.sync();
        mirror.ensurePr("ignite", 13786, first);
        Files.move(heads, temp.resolve("offline-gitbox.git"));

        mirror.sync();

        assertThat(git(mirror.repository("ignite"), "rev-parse", "refs/pull/13786/head")).isEqualTo(first);
        assertThat(mirror.status().get(0).error()).isNotBlank();
    }

    /** The opt-in default creates no mirror directory and cannot serve a filesystem path. */
    @Test
    void disabledMirrorDoesNoGitWork() {
        GitMirror mirror = mirror(false);

        mirror.sync();
        mirror.ensurePr("ignite", 13786, first);

        assertThat(Files.exists(temp.resolve("mirrors"))).isFalse();
        assertThatThrownBy(() -> mirror.repository("ignite")).isInstanceOf(GitMirror.NotReadyException.class);
    }

    /** An HTTP launch cannot start a long bootstrap and later queue after the requester has left. */
    @Test
    void refusesLaunchUntilBackgroundBootstrapCompletes() {
        GitMirror mirror = mirror(true);

        assertThatThrownBy(() -> mirror.ensurePr("ignite", 13786, first))
            .isInstanceOf(GitMirror.NotReadyException.class).hasMessageContaining("initializing");

        assertThat(Files.exists(temp.resolve("mirrors"))).isFalse();
    }

    /** A ref rename conflicting with a retained branch cannot update only part of the heads/tags batch. */
    @Test
    void failedAtomicFetchLeavesAllPreviousHeadAndTagTips() throws Exception {
        git(work, "push", heads.toString(), "HEAD:refs/heads/release/topic");
        GitMirror mirror = mirror(true);
        mirror.sync();
        commit("new master and tag");
        git(work, "tag", "-f", "release-1");
        git(heads, "update-ref", "-d", "refs/heads/release/topic");
        git(work, "push", "--force", heads.toString(), "master", "refs/tags/release-1", "HEAD:refs/heads/release");

        mirror.sync();

        Path repo = mirror.repository("ignite");
        assertThat(git(repo, "rev-parse", "refs/heads/master")).isEqualTo(first);
        assertThat(git(repo, "rev-parse", "refs/tags/release-1")).isEqualTo(first);
        assertThat(mirror.status().get(0).error()).contains("cannot lock ref");
    }

    /** A URL, path traversal or SHA expression cannot turn the public API into an arbitrary Git fetch. */
    @Test
    void rejectsUnlistedRepositoriesAndNonShaPinsBeforeCreatingFiles() {
        GitMirror mirror = mirror(true);

        for (String slug : List.of("../source", "/etc", "https://github.com/apache/ignite.git")) {
            assertThatThrownBy(() -> mirror.ensurePr(slug, 13786, first))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> mirror.repository(slug)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> mirror.ensurePr("ignite", 13786, "HEAD"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(temp.resolve("mirrors"))).isFalse();
    }

    /** Public Git subprocesses receive neither application secrets nor inherited Git URL/config overrides. */
    @Test
    void clearsApplicationSecretsAndGitOverridesFromSubprocessEnvironment() {
        ProcessBuilder builder = new ProcessBuilder("git", "version");
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "GITHUB_TOKEN", "test-secret",
            "SESSION_SECRET", "test-session", "GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "url.evil.insteadOf"));

        GitMirror.isolateEnvironment(builder);

        assertThat(builder.environment()).containsExactlyInAnyOrderEntriesOf(Map.of("PATH", "/usr/bin:/bin",
            "GIT_TERMINAL_PROMPT", "0", "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_ASKPASS", "/bin/false"));
    }

    /** A saved Python run keeps its original master commit even when master has advanced. */
    @Test
    void canRetainAndReusePinnedBaseCommit() throws Exception {
        GitMirror mirror = mirror(true);
        mirror.sync();
        String next = commit("new master");
        git(work, "push", heads.toString(), "master");
        mirror.sync();

        mirror.ensureCommit("ignite", first);

        assertThat(git(mirror.repository("ignite"), "rev-parse", "refs/heads/master")).isEqualTo(next);
        assertThat(git(mirror.repository("ignite"), "rev-parse", "refs/keep/" + first)).isEqualTo(first);
    }

    /** Python's freshly resolved master must match GitBox and is retained as an exact commit pin. */
    @Test
    void checksAndKeepsFreshBaseRevisionFromGitBox() throws Exception {
        GitMirror mirror = mirror(true);
        mirror.sync();

        mirror.ensureRevision("ignite", "refs/heads/master", first);

        assertThat(git(mirror.repository("ignite"), "rev-parse", "refs/keep/" + first)).isEqualTo(first);
    }

    /** A source that accepts HTTP but never replies cannot hold a sync beyond the command deadline. */
    @Test
    void stopsAnUnresponsiveGitSubprocess() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requested.countDown();
            try {
                release.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        var pool = Executors.newSingleThreadExecutor();
        try {
            GitMirror mirror = new GitMirror(new GitMirrorProperties(true, temp.resolve("timeout").toString(), 60L, 1L),
                Map.of("ignite", new GitMirror.Sources("http://" + server.getAddress().getHostString() + ":"
                    + server.getAddress().getPort() + "/blocked.git", prs.toString())));
            var sync = pool.submit(mirror::sync);

            assertThat(requested.await(20, TimeUnit.SECONDS)).isTrue();
            sync.get(20, TimeUnit.SECONDS);

            assertThat(mirror.status().get(0).ready()).isFalse();
            assertThat(mirror.status().get(0).error()).contains("timed out");
        }
        finally {
            release.countDown();
            server.stop(0);
            pool.shutdownNow();
        }
    }

    private GitMirror mirror(boolean enabled) {
        return new GitMirror(new GitMirrorProperties(enabled, temp.resolve("mirrors").toString(), 60L, 10L),
            Map.of("ignite", new GitMirror.Sources(heads.toString(), prs.toString())));
    }

    private String commit(String text) throws Exception {
        Files.writeString(work.resolve("code.txt"), text);
        git(work, "add", "code.txt");
        git(work, "-c", "user.name=Mirror Test", "-c", "user.email=mirror@example.invalid", "commit", "-m", text);
        return git(work, "rev-parse", "HEAD");
    }

    private static String git(Path directory, String... args) throws Exception {
        GitResult result = gitResult(directory, args);
        assertThat(result.exit()).as("%s: %s", List.of(args), result.output()).isZero();
        return result.output();
    }

    private static GitResult gitResult(Path directory, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git"));
        if (directory != null)
            command.addAll(List.of("-C", directory.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return new GitResult(process.exitValue(), output);
        }
        finally {
            process.destroyForcibly();
        }
    }

    private record GitResult(int exit, String output) {
    }
}
