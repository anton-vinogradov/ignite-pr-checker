package com.github.igniteprchecker.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** GitBox owns normal branches and tags; GitHub's PR head supplies code absent from GitBox. */
    @Test
    void combinesOfficialBranchesWithGithubPrHeads() throws Exception {
        String prHead = commit("PR only");
        git(work, "push", prs.toString(), "HEAD:refs/pull/13786/head");
        GitMirror mirror = mirror(true);

        mirror.sync();

        Path repo = mirror.repository("ignite");
        assertThat(git(repo, "rev-parse", "refs/heads/master")).isEqualTo(first);
        assertThat(git(repo, "rev-parse", "refs/tags/release-1")).isEqualTo(first);
        assertThat(git(repo, "rev-parse", "refs/pull/13786/head")).isEqualTo(prHead);
        assertThat(mirror.status()).singleElement().satisfies(s -> {
            assertThat(s.ready()).isTrue();
            assertThat(s.syncing()).isFalse();
            assertThat(s.lastSuccessAt()).isNotNull();
            assertThat(s.error()).isNull();
        });
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
        Files.move(prs, temp.resolve("offline.git"));

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
        var command = new java.util.ArrayList<>(List.of("git"));
        if (directory != null)
            command.addAll(List.of("-C", directory.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(process.exitValue()).as("%s: %s", command, output).isZero();
            return output;
        }
        finally {
            process.destroyForcibly();
        }
    }
}
