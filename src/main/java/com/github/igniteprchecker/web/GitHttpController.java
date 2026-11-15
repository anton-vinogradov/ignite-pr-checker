package com.github.igniteprchecker.web;

import com.github.igniteprchecker.mirror.GitMirror;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the public mirrors through Git's read-only smart HTTP protocol. */
@RestController
public class GitHttpController {
    private static final Logger log = LoggerFactory.getLogger(GitHttpController.class);
    static final int MAX_REQUEST = 8 * 1024 * 1024;
    private static final int MAX_HEADERS = 16 * 1024;
    private static final int TIMEOUT_SECONDS = 900;
    private final GitMirror mirror;
    private final Semaphore uploads = new Semaphore(2);
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "git-http-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    public GitHttpController(GitMirror mirror) {
        this.mirror = mirror;
    }

    @GetMapping("/api/git-mirror/status")
    public MirrorStatus status() {
        return new MirrorStatus(mirror.enabled(), mirror.status().stream().map(s ->
            new RepositoryStatus(s.slug(), s.ready(), s.syncing(), s.lastSuccessAt(), s.error() != null)).toList());
    }

    @GetMapping("/git/{slug:[a-z0-9-]+}.git/info/refs")
    public void refs(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        if (!"service=git-upload-pack".equals(request.getQueryString())) {
            response.sendError(403, "Only git-upload-pack is available");
            return;
        }
        serve(slug, "info/refs", request, response);
    }

    @PostMapping("/git/{slug:[a-z0-9-]+}.git/git-upload-pack")
    public void upload(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        if (request.getQueryString() != null
            || !"application/x-git-upload-pack-request".equals(request.getContentType())) {
            response.sendError(400, "Expected a git-upload-pack request");
            return;
        }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("gzip")) {
            response.sendError(415, "Unsupported content encoding");
            return;
        }
        if (request.getContentLengthLong() > MAX_REQUEST) {
            response.sendError(413);
            return;
        }
        serve(slug, "git-upload-pack", request, response);
    }

    private void serve(String slug, String operation, HttpServletRequest request,
        HttpServletResponse response) throws IOException {
        if (!mirror.enabled()) {
            response.sendError(404);
            return;
        }
        Path repository;
        try {
            repository = mirror.repository(slug);
        }
        catch (IllegalArgumentException e) {
            response.sendError(404);
            return;
        }
        catch (GitMirror.NotReadyException e) {
            unavailable(response);
            return;
        }
        String protocol = request.getHeader("Git-Protocol");
        if (protocol != null && !protocol.matches("version=[012]")) {
            response.sendError(400, "Unsupported Git protocol");
            return;
        }
        if (!uploads.tryAcquire()) {
            unavailable(response);
            return;
        }
        Process process = null;
        Thread input = null;
        Thread errors = null;
        java.util.concurrent.ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        ByteArrayOutputStream errorText = new ByteArrayOutputStream();
        try {
            byte[] body = operation.equals("info/refs") ? new byte[0]
                : request.getInputStream().readNBytes(MAX_REQUEST + 1);
            if (body.length > MAX_REQUEST) {
                response.sendError(413);
                return;
            }
            if (operation.equals("git-upload-pack") && request.getHeader("Content-Encoding") != null) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = gzip.readNBytes(MAX_REQUEST + 1);
                }
                catch (IOException e) {
                    response.sendError(400, "Invalid gzip body");
                    return;
                }
                if (body.length > MAX_REQUEST) {
                    response.sendError(413);
                    return;
                }
            }
            ProcessBuilder builder = new ProcessBuilder("git", "-c", "http.receivepack=false", "-c",
                "http.getanyfile=false", "-c", "core.hooksPath=/dev/null", "http-backend");
            String path = builder.environment().get("PATH");
            Map<String, String> env = builder.environment();
            env.clear();
            if (path != null)
                env.put("PATH", path);
            env.putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_TERMINAL_PROMPT", "0", "GIT_HTTP_EXPORT_ALL", "1", "GIT_HTTP_MAX_REQUEST_BUFFER", "8M",
                "GIT_PROJECT_ROOT", repository.getParent().toString(), "REQUEST_METHOD", request.getMethod(),
                "PATH_INFO", "/" + slug + ".git/" + operation, "CONTENT_LENGTH", String.valueOf(body.length)));
            env.put("QUERY_STRING", operation.equals("info/refs") ? "service=git-upload-pack" : "");
            if (operation.equals("git-upload-pack"))
                env.put("CONTENT_TYPE", "application/x-git-upload-pack-request");
            if (protocol != null)
                env.put("GIT_PROTOCOL", protocol);
            process = builder.start();
            Process running = process;
            byte[] inputBody = body;
            deadline = deadlines.schedule(() -> {
                timedOut.set(true);
                stop(running);
            }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
            input = new Thread(() -> {
                try (var stdin = running.getOutputStream()) {
                    stdin.write(inputBody);
                }
                catch (IOException ignored) {
                    // An invalid request can make Git close stdin before consuming the body.
                }
            }, "git-http-input");
            errors = new Thread(() -> {
                try (var stderr = running.getErrorStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = stderr.read(buffer)) != -1)
                        if (errorText.size() < MAX_HEADERS)
                            errorText.write(buffer, 0, Math.min(read, MAX_HEADERS - errorText.size()));
                }
                catch (IOException ignored) {
                    // The deadline and a disconnected client both close the process streams.
                }
            }, "git-http-errors");
            input.setDaemon(true);
            errors.setDaemon(true);
            input.start();
            errors.start();
            try (InputStream stdout = process.getInputStream()) {
                headers(stdout, response);
                stdout.transferTo(response.getOutputStream());
            }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0 || timedOut.get())
                throw new IOException("Git HTTP backend did not complete successfully");
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            log.warn("Git HTTP {} for {} failed{}: {}", operation, slug, timedOut.get() ? " (timeout)" : "",
                errorText.toString(StandardCharsets.UTF_8).strip());
            if (!response.isCommitted()) {
                response.reset();
                unavailable(response);
            }
        }
        finally {
            if (deadline != null)
                deadline.cancel(false);
            if (process != null)
                stop(process);
            join(input);
            join(errors);
            uploads.release();
        }
    }

    private static void headers(InputStream stream, HttpServletResponse response) throws IOException {
        int total = 0;
        boolean contentType = false;
        while (total < MAX_HEADERS) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int next;
            while ((next = stream.read()) != -1 && next != '\n') {
                if (++total >= MAX_HEADERS)
                    throw new IOException("Git HTTP headers are too large");
                line.write(next);
            }
            if (next == -1)
                throw new IOException("Git HTTP backend returned incomplete headers");
            total++;
            String text = line.toString(StandardCharsets.US_ASCII).strip();
            if (text.isEmpty()) {
                if (!contentType)
                    throw new IOException("Git HTTP backend returned no content type");
                return;
            }
            int colon = text.indexOf(':');
            if (colon < 1)
                throw new IOException("Git HTTP backend returned invalid headers");
            String name = text.substring(0, colon);
            String value = text.substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Status"))
                response.setStatus(Integer.parseInt(value.split(" ", 2)[0]));
            else if (name.equalsIgnoreCase("Content-Type")) {
                response.setContentType(value);
                contentType = true;
            }
            else if (name.equalsIgnoreCase("Cache-Control") || name.equalsIgnoreCase("Expires")
                || name.equalsIgnoreCase("Pragma"))
                response.setHeader(name, value);
        }
        throw new IOException("Git HTTP headers are too large");
    }

    private static void unavailable(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "10");
        response.sendError(503, "Git mirror is not ready or is busy; retry shortly");
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive())
            process.destroyForcibly();
    }

    private static void join(Thread thread) {
        if (thread == null)
            return;
        try {
            thread.join(1000);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void close() {
        deadlines.shutdownNow();
    }

    public record MirrorStatus(boolean enabled, List<RepositoryStatus> repositories) { }
    public record RepositoryStatus(String slug, boolean ready, boolean syncing, Instant lastSuccessAt,
        boolean lastSyncFailed) { }
}
