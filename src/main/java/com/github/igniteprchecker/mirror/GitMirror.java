package com.github.igniteprchecker.mirror;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Public Git sources only; moving PR refs and the commits already queued are kept separately. */
@Service
public class GitMirror {
    private static final Logger log = LoggerFactory.getLogger(GitMirror.class);
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final long LAUNCH_TIMEOUT_SECONDS = 30;
    private final GitMirrorProperties props;
    private final Map<String, Repo> repos = new LinkedHashMap<>();

    @Autowired
    public GitMirror(GitMirrorProperties props) {
        this(props, Map.of(
            "ignite", new Sources("https://gitbox.apache.org/repos/asf/ignite.git",
                "https://github.com/apache/ignite.git"),
            "ignite-python-thin-client", new Sources("https://gitbox.apache.org/repos/asf/ignite-python-thin-client.git",
                "https://github.com/apache/ignite-python-thin-client.git")));
    }

    GitMirror(GitMirrorProperties props, Map<String, Sources> sources) {
        this.props = props;
        Path base = Path.of(props.directory()).toAbsolutePath().normalize();
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
            repos.put(e.getKey(), new Repo(e.getKey(), base.resolve(e.getKey() + ".git"), e.getValue())));
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** The HTTP transport reads available refs; a request cannot start a fetch. */
    public Path repository(String slug) {
        Repo repo = repo(slug);
        if (!enabled() || !repo.ready)
            throw new NotReadyException("Git mirror " + slug + " is not ready");
        return repo.path;
    }

    public List<Status> status() {
        return repos.values().stream().map(r -> new Status(r.slug, r.ready, r.lock.isLocked(), r.lastSuccessAt,
            r.error)).toList();
    }

    @Scheduled(fixedDelayString = "${git-mirror.sync-interval-seconds:60}", initialDelay = 1,
        timeUnit = TimeUnit.SECONDS)
    public void sync() {
        if (!enabled())
            return;
        for (Repo repo : repos.values()) {
            if (!repo.slug.equals("ignite") && !repo.active || !repo.lock.tryLock())
                continue;
            try {
                syncLocked(repo);
            }
            catch (RuntimeException e) {
                failed(repo, e);
                log.warn("Git mirror {} sync failed: {}", repo.slug, e.getMessage());
            }
            finally {
                repo.lock.unlock();
            }
        }
    }

    /** Fetches the PR and keeps its exact requested commit reachable after later force pushes. */
    public void ensurePr(String slug, int pr, String expectedSha) {
        if (pr <= 0)
            throw new IllegalArgumentException("A positive PR number is required");
        ensureRevision(slug, "refs/pull/" + pr + "/head", expectedSha);
    }

    /** Checks a full branch or PR ref against the commit selected before this sync. */
    public void ensureRevision(String slug, String ref, String expectedSha) {
        boolean prRef = ref != null && ref.matches("refs/pull/[1-9][0-9]*/head");
        if (!prRef && (ref == null || !ref.startsWith("refs/heads/") || !ref.matches("[A-Za-z0-9_./-]+")))
            throw new IllegalArgumentException("A full Git branch or PR head ref is required");
        ensure(slug, expectedSha, (repo, deadline) -> {
            git(null, remainingSeconds(deadline), "check-ref-format", ref);
            fetch(repo, remainingSeconds(deadline), prRef ? repo.sources.prs() : repo.sources.heads(),
                "+" + ref + ":" + ref);
            String actual = git(repo.path, remainingSeconds(deadline), "rev-parse", "--verify", ref + "^{commit}")
                .strip();
            if (!expectedSha.equals(actual))
                throw new NotReadyException("Git mirror " + ref + " head is " + actual
                    + ", expected " + expectedSha + "; retry after the update finishes");
        });
    }

    /** Reuses an earlier pin; an object missed during bootstrap is fetched only by its validated SHA. */
    public void ensureCommit(String slug, String expectedSha) {
        ensure(slug, expectedSha, (repo, deadline) -> {
            String ref = "refs/keep/" + expectedSha;
            try {
                git(repo.path, remainingSeconds(deadline), "cat-file", "-e", expectedSha + "^{commit}");
            }
            catch (NotReadyException missing) {
                fetch(repo, remainingSeconds(deadline), repo.sources.prs(), expectedSha + ":" + ref);
                git(repo.path, remainingSeconds(deadline), "cat-file", "-e", expectedSha + "^{commit}");
            }
        });
    }

    private void ensure(String slug, String expectedSha, java.util.function.BiConsumer<Repo, Long> update) {
        if (!enabled())
            return;
        Repo repo = repo(slug);
        if (expectedSha == null || !expectedSha.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("A full lowercase Git SHA is required");
        repo.active = true;
        if (!repo.ready)
            throw new NotReadyException("Git mirror " + slug + " is initializing; retry after its first sync");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LAUNCH_TIMEOUT_SECONDS);
        boolean locked = false;
        try {
            locked = repo.lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked)
                throw new NotReadyException("Git mirror " + slug + " is syncing; retry the launch shortly");
            update.accept(repo, deadline);
            git(repo.path, remainingSeconds(deadline), "update-ref", "refs/keep/" + expectedSha, expectedSha);
            succeeded(repo);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotReadyException("Interrupted waiting for Git mirror " + slug, e);
        }
        catch (RuntimeException e) {
            failed(repo, e);
            throw e;
        }
        finally {
            if (locked)
                repo.lock.unlock();
        }
    }

    private Repo repo(String slug) {
        Repo repo = repos.get(slug);
        if (repo == null)
            throw new IllegalArgumentException("Unknown Git mirror repository");
        return repo;
    }

    private void syncLocked(Repo repo) {
        if (!Files.exists(repo.path.resolve("HEAD"))) {
            try {
                Files.createDirectories(repo.path.getParent());
            }
            catch (IOException e) {
                throw new NotReadyException("Cannot create Git mirror directory", e);
            }
            git(null, "init", "--bare", repo.path.toString());
        }
        if (!"true".equals(git(repo.path, "rev-parse", "--is-bare-repository").strip()))
            throw new NotReadyException("Git mirror is not a bare repository: " + repo.slug);
        git(repo.path, "config", "gc.auto", "0");
        git(repo.path, "config", "maintenance.auto", "false");
        // Both background fetches and HTTP uploads share the small host with the JVM.
        git(repo.path, "config", "pack.threads", "1");
        git(repo.path, "config", "pack.windowMemory", "32m");
        git(repo.path, "config", "pack.deltaCacheSize", "32m");
        fetch(repo, repo.sources.heads(), "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*");
        fetch(repo, repo.sources.prs(), "+refs/pull/*/head:refs/pull/*/head");
        git(repo.path, "symbolic-ref", "HEAD", "refs/heads/master");
        repo.ready = true;
        succeeded(repo);
    }

    private void fetch(Repo repo, String source, String... refs) {
        fetch(repo, props.timeoutSeconds(), source, refs);
    }

    private void fetch(Repo repo, long timeoutSeconds, String source, String... refs) {
        List<String> args = new ArrayList<>(List.of("fetch", "--quiet", "--atomic", "--no-tags", "--no-prune",
            "--no-write-fetch-head", "--no-auto-gc", source));
        args.addAll(List.of(refs));
        git(repo.path, timeoutSeconds, args.toArray(String[]::new));
    }

    private void succeeded(Repo repo) {
        repo.lastSuccessAt = Instant.now();
        repo.error = null;
    }

    private void failed(Repo repo, RuntimeException e) {
        repo.error = e.getMessage();
    }

    private String git(Path directory, String... args) {
        return git(directory, props.timeoutSeconds(), args);
    }

    private static long remainingSeconds(long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0)
            throw new NotReadyException("Git mirror launch preparation timed out");
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left));
    }

    private String git(Path directory, long timeoutSeconds, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "credential.helper=", "-c",
            "core.hooksPath=/dev/null"));
        if (directory != null) {
            command.add("-C");
            command.add(directory.toString());
        }
        command.addAll(List.of(args));
        Process process = null;
        Thread reader = null;
        LimitedOutput output = new LimitedOutput();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            isolateEnvironment(builder);
            process = builder.start();
            process.getOutputStream().close();
            InputStream stream = process.getInputStream();
            reader = new Thread(() -> output.read(stream), "git-mirror-output");
            reader.setDaemon(true);
            reader.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                throw new NotReadyException("Git mirror command timed out after " + timeoutSeconds + "s");
            long readWait = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            reader.join(readWait);
            if (reader.isAlive())
                throw new NotReadyException("Git mirror output did not finish within its command timeout");
            if (process.exitValue() != 0)
                throw new NotReadyException("Git mirror command failed: " + output.text().strip());
            return output.text();
        }
        catch (IOException e) {
            throw new NotReadyException("Cannot run Git mirror command", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotReadyException("Git mirror command interrupted", e);
        }
        finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                }
                catch (IOException ignored) {
                    // Closing a killed process can report its already closed pipe.
                }
            }
            if (reader != null && reader.isAlive())
                reader.interrupt();
        }
    }

    static void isolateEnvironment(ProcessBuilder builder) {
        Map<String, String> env = builder.environment();
        String path = env.get("PATH");
        env.clear();
        if (path != null)
            env.put("PATH", path);
        env.putAll(Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_ASKPASS", "/bin/false"));
    }

    public record Status(String slug, boolean ready, boolean syncing, Instant lastSuccessAt, String error) {
    }

    record Sources(String heads, String prs) {
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class NotReadyException extends RuntimeException {
        public NotReadyException(String message) {
            super(message);
        }

        public NotReadyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Repo {
        final String slug;
        final Path path;
        final Sources sources;
        final ReentrantLock lock = new ReentrantLock();
        volatile boolean active;
        volatile boolean ready;
        volatile Instant lastSuccessAt;
        volatile String error;

        Repo(String slug, Path path, Sources sources) {
            this.slug = slug;
            this.path = path;
            this.sources = sources;
        }
    }

    private static final class LimitedOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        void read(InputStream stream) {
            try (stream) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    synchronized (bytes) {
                        bytes.write(buf, 0, Math.min(n, OUTPUT_LIMIT - bytes.size()));
                    }
                }
            }
            catch (IOException ignored) {
                // A timeout closes the pipe after killing Git.
            }
        }

        String text() {
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
