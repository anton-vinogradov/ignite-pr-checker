package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.GitProxyProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Forwards only public Git upload-pack requests; callers cannot select an upstream or send credentials to it. */
@RestController
public class GitHttpController {
    private static final Logger log = LoggerFactory.getLogger(GitHttpController.class);
    private static final int MAX_REQUEST = 8 * 1024 * 1024;
    private static final int MAX_REQUESTS = 2;
    private final boolean enabled;
    private final RestClient github;
    private final Semaphore requests = new Semaphore(MAX_REQUESTS);
    private final Map<String, Repository> repositories = new LinkedHashMap<>();

    @Autowired
    public GitHttpController(GitProxyProperties props) {
        this(props, URI.create("https://github.com"));
    }

    GitHttpController(GitProxyProperties props, URI upstream) {
        enabled = props.enabled();
        github = RestClient.builder().baseUrl(upstream.toString())
            .requestFactory(OutboundHttp.withPatchNoRedirects(OutboundHttp.READ_TIMEOUT)).build();
        for (String slug : List.of("ignite", "ignite-python-thin-client", "ignite-php-thin-client",
            "ignite-nodejs-thin-client", "ignite-release", "ignite-extensions"))
            repositories.put(slug, new Repository());
    }

    @GetMapping("/api/git-proxy/status")
    public ProxyStatus status() {
        return new ProxyStatus(enabled, MAX_REQUESTS - requests.availablePermits(), repositories.entrySet().stream()
            .map(e -> new RepositoryStatus(e.getKey(), e.getValue().active.get(), e.getValue().lastSuccessAt,
                e.getValue().lastRequestFailed)).toList());
    }

    @GetMapping("/git/{slug:[a-z0-9-]+}.git/info/refs")
    public void refs(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        if (!enabled) {
            response.sendError(404);
            return;
        }
        if (!"service=git-upload-pack".equals(request.getQueryString())) {
            response.sendError(403, "Only git-upload-pack is available");
            return;
        }
        serve(slug, "info/refs?service=git-upload-pack", request, response);
    }

    @PostMapping("/git/{slug:[a-z0-9-]+}.git/git-upload-pack")
    public void upload(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        if (!enabled) {
            response.sendError(404);
            return;
        }
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

    private void serve(String slug, String operation, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
        Repository repository = repositories.get(slug);
        if (repository == null) {
            response.sendError(404);
            return;
        }
        if (!requests.tryAcquire()) {
            response.setHeader("Retry-After", "10");
            response.sendError(503, "Git proxy is busy; retry shortly");
            return;
        }
        repository.active.incrementAndGet();
        boolean success = false;
        try {
            byte[] body = request.getMethod().equals("POST")
                ? request.getInputStream().readNBytes(MAX_REQUEST + 1) : new byte[0];
            if (body.length > MAX_REQUEST) {
                response.sendError(413);
                return;
            }
            RestClient.RequestBodySpec outgoing = github.method(HttpMethod.valueOf(request.getMethod()))
                .uri("/apache/" + slug + ".git/" + operation).header("User-Agent", "Ignite-PR-Checker");
            String protocol = request.getHeader("Git-Protocol");
            if (protocol != null)
                outgoing.header("Git-Protocol", protocol);
            if (request.getMethod().equals("POST")) {
                outgoing.contentType(MediaType.parseMediaType("application/x-git-upload-pack-request"));
                String encoding = request.getHeader("Content-Encoding");
                if (encoding != null)
                    outgoing.header("Content-Encoding", encoding);
                outgoing.body(body);
            }
            success = Boolean.TRUE.equals(outgoing.exchange((sent, upstream) -> {
                int status = upstream.getStatusCode().value();
                if (upstream.getStatusCode().is3xxRedirection()) {
                    response.sendError(502, "Git upstream redirected the request");
                    return false;
                }
                response.setStatus(status);
                for (String header : List.of("Content-Type", "Cache-Control", "Expires", "Pragma")) {
                    String value = upstream.getHeaders().getFirst(header);
                    if (value != null)
                        response.setHeader(header, value);
                }
                upstream.getBody().transferTo(response.getOutputStream());
                return status >= 200 && status < 300;
            }));
            if (success)
                repository.lastSuccessAt = Instant.now();
        }
        catch (RestClientException | IOException e) {
            log.warn("Git proxy for {} failed: {}", slug, e.toString());
            if (!response.isCommitted()) {
                response.reset();
                response.sendError(502, "Git upstream is unavailable");
            }
        }
        finally {
            repository.lastRequestFailed = !success;
            repository.active.decrementAndGet();
            requests.release();
        }
    }

    public record ProxyStatus(boolean enabled, int activeGitRequests, List<RepositoryStatus> repositories) { }
    public record RepositoryStatus(String slug, int activeGitRequests, Instant lastSuccessAt,
        boolean lastRequestFailed) { }

    private static final class Repository {
        final AtomicInteger active = new AtomicInteger();
        volatile Instant lastSuccessAt;
        volatile boolean lastRequestFailed;
    }
}
