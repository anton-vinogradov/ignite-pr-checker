package com.github.igniteprchecker.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

/** Lists open pull requests of the configured GitHub repo, cached to stay within the API rate limit. */
@Component
public class GithubClient implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(GithubClient.class);

    /** Validates a user's PAT: their GitHub login when the token works. */
    public java.util.Optional<String> ghUser(String pat) {
        try {
            java.util.Map<?, ?> u = recorded("user", () -> http.get().uri(URI.create(props.apiUrl() + "/user"))
                .header("Authorization", "Bearer " + pat)
                .header("Accept", "application/vnd.github+json")
                .retrieve().body(java.util.Map.class));

            return java.util.Optional.ofNullable(u == null ? null : (String)u.get("login"));
        }
        catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * GitHub's own spelling of a login, or empty when GitHub has no such user (or the text cannot be
     * a login at all). Throws when GitHub cannot be asked.
     */
    public java.util.Optional<String> canonicalLogin(String login) {
        if (!LOGIN.matcher(login).matches())
            return java.util.Optional.empty();

        try {
            java.util.Map<?, ?> u = recorded("user", () -> appGet(props.apiUrl() + "/users/" + login)
                .body(java.util.Map.class));

            return java.util.Optional.ofNullable(u == null ? null : (String)u.get("login"));
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        }
    }

    /** What GitHub allows in a login: letters, digits and single hyphens inside, up to 39 characters. */
    private static final java.util.regex.Pattern LOGIN =
        java.util.regex.Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}");

    /** Posts a comment to the PR under the USER'S OWN PAT; its id (for later edits) and html url. */
    public PostedComment addPrComment(String pat, int prNumber, String body) {
        java.util.Map<?, ?> c = recorded("prComment", () -> http.post()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return c == null ? new PostedComment(0, "")
            : new PostedComment(((Number)c.get("id")).longValue(), String.valueOf(c.get("html_url")));
    }

    /**
     * Posts a PR comment under the APP's own token ({@link #appAccount()}): the onboarding reply, a hint or an
     * explanation to a commander. False when no app token.
     */
    public boolean addPrCommentAsApp(int prNumber, String body) {
        if (props.token() == null || props.token().isBlank())
            return false;

        recorded("onboard", () -> http.post()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return true;
    }

    /** Reacts to a comment from the app account ({@link #appAccount()}) — the ack for PAT-less commanders. */
    public boolean reactToCommentAsApp(long commentId, String content) {
        if (props.token() == null || props.token().isBlank())
            return false;

        recorded("react", () -> http.post()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/comments/" + commentId + "/reactions"))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("content", content))
            .retrieve().body(java.util.Map.class));

        return true;
    }

    /** Posts a PR comment from the APP's account, returning its id/url — the PAT-less narration thread. */
    public PostedComment addPrCommentAsAppWithId(int prNumber, String body) {
        if (props.token() == null || props.token().isBlank())
            return null;

        java.util.Map<?, ?> c = recorded("prComment", () -> http.post()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return c == null ? null
            : new PostedComment(((Number)c.get("id")).longValue(), String.valueOf(c.get("html_url")));
    }

    /** Edits the APP's own narration comment in place. False when no app token is configured. */
    public boolean updatePrCommentAsApp(long commentId, String body) {
        if (props.token() == null || props.token().isBlank())
            return false;

        recorded("prCommentEdit", () -> http.patch()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/comments/" + commentId))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return true;
    }

    /** The text of a comment of the repo; empty when GitHub has no such comment. */
    public java.util.Optional<String> commentBody(long commentId) {
        try {
            java.util.Map<?, ?> c = recorded("comment", () -> appGet(
                props.apiUrl() + "/repos/" + props.repo() + "/issues/comments/" + commentId).body(java.util.Map.class));

            return java.util.Optional.ofNullable(c == null ? null : (String)c.get("body"));
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        }
    }

    /** Whether a PR is still open, and its title; empty when GitHub has no such PR. */
    public java.util.Optional<PullState> pullState(int prNumber) {
        try {
            java.util.Map<?, ?> pr = recorded("prState", () -> appGet(
                props.apiUrl() + "/repos/" + props.repo() + "/pulls/" + prNumber).body(java.util.Map.class));

            return java.util.Optional.ofNullable(pr == null ? null : new PullState((String)pr.get("title"),
                "open".equals(pr.get("state")), Boolean.TRUE.equals(pr.get("merged"))));
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        }
    }

    /** A PR as GitHub has it now: its title, whether it is open, and whether it was merged. */
    public record PullState(String title, boolean open, boolean merged) {
    }

    /** The PR's author and head (source branch) coordinates — where a style-fix commit must go. */
    public PrHead prHead(int prNumber) {
        java.util.Map<?, ?> pr = recorded("prHead", () -> appGet(
            props.apiUrl() + "/repos/" + props.repo() + "/pulls/" + prNumber).body(java.util.Map.class));

        java.util.Map<?, ?> user = (java.util.Map<?, ?>)pr.get("user");
        java.util.Map<?, ?> head = (java.util.Map<?, ?>)pr.get("head");
        java.util.Map<?, ?> headRepo = (java.util.Map<?, ?>)head.get("repo");

        return new PrHead((String)user.get("login"), (String)headRepo.get("full_name"),
            (String)head.get("ref"), (String)head.get("sha"));
    }

    /** Whether a PR was merged or closed, and when: one call. */
    public PrOutcome prOutcome(int prNumber) {
        java.util.Map<?, ?> pr = recorded("prState", () -> appGet(
            props.apiUrl() + "/repos/" + props.repo() + "/pulls/" + prNumber).body(java.util.Map.class));
        if (pr == null)
            throw new IllegalStateException("GitHub sent no PR " + prNumber);

        Object mergedAt = pr.get("merged_at");
        java.util.Map<?, ?> head = pr.get("head") instanceof java.util.Map<?, ?> h ? h : java.util.Map.of();

        return new PrOutcome(mergedAt != null, "closed".equals(pr.get("state")),
            mergedAt == null ? 0 : java.time.Instant.parse(mergedAt.toString()).getEpochSecond(),
            (String)pr.get("merge_commit_sha"), (String)head.get("sha"), (String)pr.get("title"));
    }

    /**
     * How a PR stands on GitHub: an open one is neither {@code merged} nor {@code closed}, a merged one is both.
     * {@code mergedAt} is epoch seconds, 0 when not merged.
     */
    public record PrOutcome(boolean merged, boolean closed, long mergedAt, String mergeCommitSha, String headSha,
        String title) {
    }

    /** How many commits {@code head} is ahead of {@code base}, and the head's short sha — for staleness. */
    public Ahead compareAhead(String base, String head) {
        if (base == null || head == null || base.equals(head))
            return new Ahead(0, head == null ? "" : head.substring(0, Math.min(7, head.length())), false);

        try {
            java.util.Map<?, ?> cmp = recorded("compare", () -> appGet(
                props.apiUrl() + "/repos/" + props.repo() + "/compare/" + base + "..." + head)
                .body(java.util.Map.class));
            int ahead = cmp == null || cmp.get("ahead_by") == null ? -1 : ((Number)cmp.get("ahead_by")).intValue();
            Object status = cmp == null ? null : cmp.get("status");
            // After a rebase GitHub counts the master commits under the new head too, so the count says little.
            boolean rewritten = "diverged".equals(status) || "behind".equals(status);

            return new Ahead(ahead, head.substring(0, Math.min(7, head.length())), rewritten);
        }
        catch (RuntimeException e) {
            // compare can 404 if the base sha was garbage-collected; still flag the mismatch
            return new Ahead(-1, head.substring(0, Math.min(7, head.length())), false);
        }
    }

    /**
     * Result of a base…head compare: commits ahead ({@code -1} if unknown), the head short sha, and whether the
     * head no longer contains base (a rebase or force-push rewrote the branch).
     */
    public record Ahead(int ahead, String headShort, boolean rewritten) {
    }

    /** The files that differ between {@code base} and {@code head}, as GitHub's compare lists them. */
    public Changes changesBetween(String base, String head) {
        java.util.Map<?, ?> cmp = recorded("compare", () -> appGet(
            props.apiUrl() + "/repos/" + props.repo() + "/compare/" + base + "..." + head).body(java.util.Map.class));
        if (cmp == null)
            throw new IllegalStateException("GitHub sent no comparison of " + base + " and " + head);

        java.util.List<?> files = cmp.get("files") instanceof java.util.List<?> l ? l : java.util.List.of();
        java.util.Map<String, String> statuses = new java.util.HashMap<>();
        for (Object f : files) {
            if (f instanceof java.util.Map<?, ?> m && m.get("filename") instanceof String path)
                statuses.put(path, m.get("status") instanceof String status ? status : "");
        }
        Object status = cmp.get("status");

        return new Changes(java.util.Map.copyOf(statuses), "diverged".equals(status) || "behind".equals(status),
            files.size() < COMPARE_FILES_MAX);
    }

    /** The most files GitHub's compare lists. */
    private static final int COMPARE_FILES_MAX = 300;

    /**
     * Files that differ between two commits, each with GitHub's status of it: added, modified, renamed (to this
     * path), and so on. {@code complete}: that is all of them; GitHub cuts the list at 300. {@code rewritten}: head
     * no longer contains base, and the list then holds the base branch's changes as well.
     */
    public record Changes(java.util.Map<String, String> files, boolean rewritten, boolean complete) {
        /** Two commits with the same files. */
        public static final Changes NONE = new Changes(java.util.Map.of(), false, true);
    }

    /** Paths of the PR's changed (not removed) .java files, capped at 300. */
    public java.util.List<String> prJavaFiles(int prNumber) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            int p = page;
            java.util.List<?> files = recorded("prFiles", () -> appGet(
                props.apiUrl() + "/repos/" + props.repo() + "/pulls/" + prNumber
                    + "/files?per_page=100&page=" + p).body(java.util.List.class));
            if (files == null || files.isEmpty())
                break;
            for (Object o : files) {
                java.util.Map<?, ?> f = (java.util.Map<?, ?>)o;
                if (String.valueOf(f.get("filename")).endsWith(".java") && !"removed".equals(f.get("status")))
                    out.add((String)f.get("filename"));
            }
            if (files.size() < 100)
                break;
        }

        return out;
    }

    /**
     * The PR's test classes it adds or changes ({@code *Test.java}, not removed), with how each changed:
     * "added", "modified", "renamed"... One call, more only for a PR of over 100 files, up to 300.
     */
    public java.util.List<PrFile> prTestFiles(int prNumber) {
        java.util.List<PrFile> out = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            int p = page;
            java.util.List<?> files = recorded("prFiles", () -> appGet(
                props.apiUrl() + "/repos/" + props.repo() + "/pulls/" + prNumber
                    + "/files?per_page=100&page=" + p).body(java.util.List.class));
            if (files == null || files.isEmpty())
                break;
            for (Object o : files) {
                java.util.Map<?, ?> f = (java.util.Map<?, ?>)o;
                String name = String.valueOf(f.get("filename"));
                if (name.endsWith("Test.java") && !"removed".equals(f.get("status")))
                    out.add(new PrFile(name, String.valueOf(f.get("status"))));
            }
            if (files.size() < 100)
                break;
        }

        return out;
    }

    /** A file a PR changes: its path and how GitHub says it changed. */
    public record PrFile(String path, String status) {
    }

    /**
     * The checks GitHub shows for a commit, the latest of each name, up to 100. A check of GitHub Actions is one job
     * of a workflow, and has the job's id.
     */
    public java.util.List<CheckRun> checkRuns(String sha) {
        CheckRuns runs = recorded("checkRuns", () -> appGet(
            props.apiUrl() + "/repos/" + props.repo() + "/commits/" + sha + "/check-runs?per_page=100")
            .body(CheckRuns.class));

        return runs == null || runs.checkRuns() == null ? java.util.List.of() : runs.checkRuns();
    }

    /** A check of a commit: GitHub's {@code status} (queued, in_progress, completed) and {@code conclusion}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckRun(long id, String name, String status, String conclusion,
        @JsonProperty("html_url") String htmlUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckRuns(@JsonProperty("check_runs") java.util.List<CheckRun> checkRuns) {
    }

    /** A GitHub Actions job: how far it got, and its steps in order. */
    public Job job(long jobId) {
        Job job = recorded("job", () -> appGet(props.apiUrl() + "/repos/" + props.repo() + "/actions/jobs/" + jobId)
            .body(Job.class));
        if (job == null)
            throw new IllegalStateException("GitHub sent no job " + jobId);

        return job.steps() == null ? new Job(job.status(), java.util.List.of()) : job;
    }

    /** A job and its steps, each with GitHub's {@code status} and {@code conclusion}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Job(String status, java.util.List<Step> steps) {
        /** One step of a job, by the name the workflow gives it. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Step(String name, String status, String conclusion) {
        }
    }

    /**
     * Reads the log of a finished GitHub Actions job with {@code reader}, line by line as it comes in; empty without
     * the app token, as GitHub gives logs only to a signed-in caller. GitHub answers with a short-lived address of the
     * log on another host, which gets the request without the token.
     */
    public <T> Optional<T> jobLog(long jobId, Function<Stream<String>, T> reader) {
        if (props.token() == null || props.token().isBlank())
            return Optional.empty();

        URI asked = URI.create(props.apiUrl() + "/repos/" + props.repo() + "/actions/jobs/" + jobId + "/logs");
        Logged<T> first = recorded("jobLog", () -> noRedirects.get().uri(asked)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + props.token())
            .exchange((request, response) -> {
                URI moved = response.getHeaders().getLocation();
                if (response.getStatusCode().is3xxRedirection() && moved != null)
                    return new Logged<T>(null, asked.resolve(moved));

                return new Logged<>(readLog(response, reader, jobId), null);
            }));
        if (first.at() == null)
            return Optional.ofNullable(first.read());

        return Optional.ofNullable(recorded("jobLog", () -> http.get().uri(first.at())
            .exchange((request, response) -> readLog(response, reader, jobId))));
    }

    private static <T> T readLog(ClientHttpResponse response, Function<Stream<String>, T> reader, long jobId)
        throws IOException {
        if (!response.getStatusCode().is2xxSuccessful())
            throw new RestClientResponseException("GitHub answered " + response.getStatusCode().value()
                + " for the log of job " + jobId, response.getStatusCode(), response.getStatusText(),
                response.getHeaders(), null, null);

        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.getBody(),
            StandardCharsets.UTF_8))) {
            return reader.apply(lines.lines());
        }
    }

    /** A job's log as the first answer gave it: read there, or to be read {@code at} another address. */
    private record Logged<T>(T read, URI at) {
    }

    /** Raw contents of one file at a ref, from an arbitrary (fork) repo. */
    public String rawFile(String repo, String ref, String path) {
        return recorded("rawFile", () -> rawGet(repo, ref, path).retrieve().body(String.class));
    }

    /**
     * Raw contents of one file at a ref, as UTF-8, read only while they fit in {@code maxBytes}; empty when the file
     * is bigger. A file of a pull request can be of any size, and held whole it took the heap.
     */
    public java.util.Optional<String> rawFileUpTo(String repo, String ref, String path, int maxBytes) {
        return recorded("rawFile", () -> rawGet(repo, ref, path).exchange((request, response) -> {
            if (response.getStatusCode().isError())
                throw new RestClientResponseException("GitHub answered "
                    + response.getStatusCode().value() + " for " + path, response.getStatusCode(),
                    response.getStatusText(), response.getHeaders(), null, null);
            if (response.getHeaders().getContentLength() > maxBytes)
                return java.util.Optional.<String>empty();

            try (InputStream body = response.getBody()) {
                byte[] read = body.readNBytes(maxBytes + 1);

                return read.length > maxBytes ? java.util.Optional.<String>empty()
                    : java.util.Optional.of(new String(read, StandardCharsets.UTF_8));
            }
        }));
    }

    /**
     * A request for one file's raw contents. Each segment of the path is encoded: a "#" or "?" in a file name
     * used to cut the request short.
     */
    private RestClient.RequestHeadersSpec<?> rawGet(String repo, String ref, String path) {
        String encoded = Arrays.stream(path.split("/", -1))
            .map(seg -> UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("/"));
        RestClient.RequestHeadersSpec<?> req = http.get()
            .uri(URI.create(props.apiUrl() + "/repos/" + repo + "/contents/" + encoded + "?ref="
                + UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8)))
            .header("Accept", "application/vnd.github.raw+json");
        if (props.token() != null && !props.token().isBlank())
            req = req.header("Authorization", "Bearer " + props.token());

        return req;
    }

    /**
     * Creates ONE commit updating the given files on a branch — pure Git Data API, no clone: blobs ->
     * tree (on top of the parent's) -> commit -> ref. Runs under the USER'S OWN PAT (their fork,
     * their branch, their authorship). Returns the new commit sha; fails if the branch moved away
     * from {@code parentSha} (never force-pushes over someone's newer work).
     */
    public String commitFiles(String pat, String repo, String branch, String parentSha,
        java.util.Map<String, String> files, String message) {
        java.util.Map<?, ?> parent = patGet(pat,
            props.apiUrl() + "/repos/" + repo + "/git/commits/" + parentSha).body(java.util.Map.class);
        String baseTree = (String)((java.util.Map<?, ?>)parent.get("tree")).get("sha");

        java.util.List<java.util.Map<String, String>> tree = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> f : files.entrySet()) {
            java.util.Map<?, ?> blob = recorded("styleCommit", () -> patPost(pat,
                props.apiUrl() + "/repos/" + repo + "/git/blobs",
                java.util.Map.of("content", f.getValue(), "encoding", "utf-8")).body(java.util.Map.class));
            tree.add(java.util.Map.of("path", f.getKey(), "mode", "100644", "type", "blob",
                "sha", (String)blob.get("sha")));
        }

        java.util.Map<?, ?> newTree = recorded("styleCommit", () -> patPost(pat,
            props.apiUrl() + "/repos/" + repo + "/git/trees",
            java.util.Map.of("base_tree", baseTree, "tree", tree)).body(java.util.Map.class));

        java.util.Map<?, ?> commit = recorded("styleCommit", () -> patPost(pat,
            props.apiUrl() + "/repos/" + repo + "/git/commits",
            java.util.Map.of("message", message, "tree", newTree.get("sha"),
                "parents", java.util.List.of(parentSha))).body(java.util.Map.class));

        String sha = (String)commit.get("sha");
        recorded("styleCommit", () -> http.patch()
            .uri(URI.create(props.apiUrl() + "/repos/" + repo + "/git/refs/heads/" + branch))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("sha", sha, "force", false))
            .retrieve().body(java.util.Map.class));

        return sha;
    }

    private RestClient.ResponseSpec appGet(String url) {
        RestClient.RequestHeadersSpec<?> req = http.get().uri(URI.create(url))
            .header("Accept", "application/vnd.github+json");
        if (props.token() != null && !props.token().isBlank())
            req = req.header("Authorization", "Bearer " + props.token());

        return req.retrieve();
    }

    private RestClient.ResponseSpec patGet(String pat, String url) {
        return http.get().uri(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + pat)
            .retrieve();
    }

    private RestClient.ResponseSpec patPost(String pat, String url, Object body) {
        return http.post().uri(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + pat)
            .body(body)
            .retrieve();
    }

    /** A PR's author and source-branch coordinates. */
    public record PrHead(String authorLogin, String headRepo, String headRef, String headSha) {
    }

    /** Replaces the body of an existing comment — the verdict lives in ONE comment that updates. */
    public void updatePrComment(String pat, long commentId, String body) {
        recorded("prCommentEdit", () -> http.patch()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/comments/" + commentId))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));
    }

    public record PostedComment(long id, String htmlUrl) {
    }

    /** The checks of a PR on GitHub, where "Check java code" runs checkstyle. */
    public String checksUrl(int prNumber) {
        return "https://github.com/" + props.repo() + "/pull/" + prNumber + "/checks";
    }

    /** Where a comment of a PR is seen on GitHub. */
    public String commentUrl(int prNumber, long commentId) {
        return "https://github.com/" + props.repo() + "/pull/" + prNumber + "#issuecomment-" + commentId;
    }

    /**
     * Issue/PR comments of the whole repo updated since the given instant (ISO-8601), oldest first —
     * ONE call covers every open PR, which is what makes a minute-level command poll affordable. A
     * full page means there is more: the next pages are read too, up to {@link #COMMENT_PAGES}.
     */
    public java.util.List<IssueComment> recentIssueComments(String sinceIso) {
        java.util.List<IssueComment> out = new java.util.ArrayList<>();
        for (int page = 1; page <= COMMENT_PAGES; page++) {
            URI uri = URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/comments?since="
                + sinceIso + "&sort=updated&direction=asc&per_page=" + COMMENT_PAGE_SIZE + "&page=" + page);

            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(uri)
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            RestClient.RequestHeadersSpec<?> r = req;
            IssueComment[] comments = recorded("comments", () -> r.retrieve().body(IssueComment[].class));
            if (comments == null)
                break;

            out.addAll(java.util.List.of(comments));
            if (comments.length < COMMENT_PAGE_SIZE)
                break;
            if (page == COMMENT_PAGES)
                log.warn("more than {} comments updated since {}: the rest are not read", out.size(), sinceIso);
        }

        return out;
    }

    private static final int COMMENT_PAGE_SIZE = 100;

    /** A minute of a busy repo is a few comments; ten full pages only follow a long outage. */
    private static final int COMMENT_PAGES = 10;

    /** Reacts to an issue/PR comment under the USER'S OWN PAT (content: rocket, confused, ...). */
    public void reactToComment(String pat, long commentId, String content) {
        recorded("react", () -> http.post()
            .uri(URI.create(props.apiUrl() + "/repos/" + props.repo() + "/issues/comments/" + commentId + "/reactions"))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("content", content))
            .retrieve().body(java.util.Map.class));
    }

    /** One repo comment from the poll: {@code htmlUrl} tells a PR comment from a plain issue's. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssueComment(long id, String body,
        @JsonProperty("html_url") String htmlUrl, @JsonProperty("created_at") String createdAt, GhUser user) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GhUser(String login) {
    }

    /**
     * This tool's own repo: its stars for the "Star" button (fetched server-side so browser blockers don't hide it)
     * and its releases.
     */
    public static final String SELF_REPO = "anton-vinogradov/ignite-pr-checker";

    private final RestClient http;

    /** For calls whose redirect leads to another host: it is followed by hand, without the token. */
    private final RestClient noRedirects;

    private final GithubProperties props;
    private final long ttlMs;
    private final ObjectMapper mapper;

    private volatile List<PrSummary> cache;

    private volatile long cacheTs;

    private volatile int starCount = -1;

    private volatile String releaseTag;

    private volatile long releaseTs;

    private volatile Map<String, Object> rateCache;

    /** When the last fetch of the stars and the rate limit ended, failed or not. */
    private volatile long ownStatsTs;

    private final AtomicBoolean ownStatsFetching = new AtomicBoolean();

    /** The status page polls every few seconds and must not wait for GitHub, which may take a minute to give up. */
    private final ExecutorService ownStatsFetch = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "github-own-stats");
        t.setDaemon(true);
        return t;
    });

    private final Metrics metrics;

    @Autowired
    public GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics) {
        this(props, mapper, metrics, RestClient.builder()
            .requestFactory(OutboundHttp.withPatch(props.readTimeout()))
            .build(), RestClient.builder()
            .requestFactory(OutboundHttp.withPatchNoRedirects(props.readTimeout()))
            .build());
    }

    /** A client whose calls all go through {@code http}, which hands a redirect back as it is: a test's stub. */
    GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics, RestClient http) {
        this(props, mapper, metrics, http, http);
    }

    private GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics, RestClient http,
        RestClient noRedirects) {
        this.props = props;
        this.ttlMs = props.cacheSeconds() * 1000L;
        this.mapper = mapper;
        this.metrics = metrics;
        this.http = http;
        this.noRedirects = noRedirects;
    }

    /** Runs a GitHub call, recording its category, outcome and latency for the status page. */
    private <T> T recorded(String category, Supplier<T> call) {
        long t0 = System.nanoTime();
        try {
            T result = call.get();
            metrics.recordGithub(category, true, (System.nanoTime() - t0) / 1_000_000L);

            return result;
        }
        catch (RuntimeException e) {
            metrics.recordGithub(category, false, (System.nanoTime() - t0) / 1_000_000L);
            throw OutboundHttp.naming("GitHub", e);
        }
    }

    /** Open PRs, most recently updated first. Returns the last good result (or empty) if GitHub is unavailable. */
    public List<PrSummary> openPrs() {
        long now = System.currentTimeMillis();
        List<PrSummary> cached = cache;
        if (cached != null && now - cacheTs < ttlMs)
            return cached;

        try {
            // sort=updated so recently active PRs (the ones being worked on) are at the top, rather
            // than merely the newest-numbered ones.
            URI uri = URI.create(props.apiUrl() + "/repos/" + props.repo()
                + "/pulls?state=open&sort=updated&direction=desc&per_page=50");

            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(uri)
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            RestClient.RequestHeadersSpec<?> r = req;
            GhPr[] prs = recorded("prs", () -> r.retrieve().body(GhPr[].class));

            // triggeredBy is filled in per-request by PrsController (it's TeamCity data, and per-user); the
            // shared GitHub list leaves it null.
            List<PrSummary> result = prs == null ? List.of()
                : Arrays.stream(prs).map(p -> new PrSummary(p.number(), p.title(), p.htmlUrl(), null, null, null,
                    p.head() == null ? null : p.head().sha(), null)).toList();

            // Never overwrite a good list with an empty one (e.g. a transient/parsed-away response).
            if (!result.isEmpty()) {
                cache = result;
                cacheTs = now;
                return result;
            }

            return cached != null ? cached : result;
        }
        catch (Exception e) {
            return cached != null ? cached : List.of();
        }
    }

    /**
     * The GitHub account the checker writes as, the one {@code GITHUB_TOKEN} belongs to: onboarding replies,
     * reactions, hints and the run narration of users without a GitHub token of their own come from it.
     * Checked once the service is up; while GitHub could not say, asked again at most every 10 minutes.
     */
    public AppAccount appAccount() {
        AppAccount known = appAccount;
        long now = System.currentTimeMillis();
        if (AppAccount.UNKNOWN.equals(known.state()) && now - appAccountCheckedAt > APP_ACCOUNT_RECHECK_MS) {
            appAccountCheckedAt = now;
            ownStatsFetch.execute(this::checkAppAccount);
        }

        return known;
    }

    @EventListener(ApplicationReadyEvent.class)
    void checkAppAccountOnStartup() {
        ownStatsFetch.execute(this::checkAppAccount);
    }

    /** Asks GitHub whom the app token belongs to, and whether that account may push to the repo. */
    void checkAppAccount() {
        appAccountCheckedAt = System.currentTimeMillis();
        if (props.token() == null || props.token().isBlank()) {
            appAccount = new AppAccount(AppAccount.NONE, null, null);
            log.info("no GITHUB_TOKEN: the checker reads GitHub at 60 requests an hour and writes nothing there; "
                + "commands of users without their own GitHub token get no reaction and no narration");

            return;
        }

        String login;
        try {
            java.util.Map<?, ?> u = recorded("appUser", () -> appGet(props.apiUrl() + "/user").body(java.util.Map.class));
            login = u == null ? null : (String)u.get("login");
        }
        catch (org.springframework.web.client.RestClientResponseException e) {
            int status = e.getStatusCode().value();
            appAccount = new AppAccount(status == 401 || status == 403 ? AppAccount.REFUSED : AppAccount.UNKNOWN, null,
                null);
            log.warn("GitHub answered {} when asked whom GITHUB_TOKEN belongs to: {}", status,
                status == 401 || status == 403 ? "the token is refused, nothing is written as the app account"
                    : "asked again later");

            return;
        }
        catch (RuntimeException e) {
            appAccount = new AppAccount(AppAccount.UNKNOWN, null, null);
            log.warn("could not ask GitHub whom GITHUB_TOKEN belongs to, asked again later: {}", e.toString());

            return;
        }

        Boolean canPush = canPush();
        appAccount = new AppAccount(AppAccount.OK, login, canPush);
        if (Boolean.TRUE.equals(canPush))
            log.warn("GITHUB_TOKEN belongs to @{}, who can push to {}: anyone who reads the server's token can too. "
                + "Use an account without write access to the repo", login, props.repo());
        else
            log.info("GitHub app account: @{} (GITHUB_TOKEN) writes the onboarding replies, reactions and narration "
                + "of users without their own GitHub token", login);
    }

    /** Whether the app account may push to the repo; null when GitHub does not say. */
    private Boolean canPush() {
        try {
            java.util.Map<?, ?> repo = recorded("appRepo", () -> appGet(props.apiUrl() + "/repos/" + props.repo())
                .body(java.util.Map.class));
            Object perms = repo == null ? null : repo.get("permissions");

            return perms instanceof java.util.Map<?, ?> m && m.get("push") instanceof Boolean push ? push : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static final long APP_ACCOUNT_RECHECK_MS = 10 * 60_000L;

    private volatile AppAccount appAccount = new AppAccount(AppAccount.UNKNOWN, null, null);

    private volatile long appAccountCheckedAt;

    /**
     * Whom {@code GITHUB_TOKEN} belongs to. {@code state}: "ok" with the {@code login} (and {@code canPush}, whether
     * it may push to the repo, null when unknown), "none" without a token, "refused" when GitHub refuses it,
     * "unknown" until GitHub has said.
     */
    public record AppAccount(String state, String login, Boolean canPush) {
        static final String OK = "ok";

        static final String NONE = "none";

        static final String REFUSED = "refused";

        static final String UNKNOWN = "unknown";

        /** What anyone may see: whether the account can push says how much a leaked server token is worth. */
        public AppAccount anonymous() {
            return new AppAccount(state, login, null);
        }
    }

    /** Number of open PRs currently cached (for the status page). */
    public int prCount() {
        List<PrSummary> c = cache;
        return c == null ? 0 : c.size();
    }

    /**
     * Star count of this tool's own repo; -1 until the first fetch. Answers at once with the last known value and,
     * when it is a minute old, fetches a fresh one in the background together with {@link #rateLimit()}.
     */
    public int starCount() {
        refreshOwnStats();

        return starCount;
    }

    /** GitHub API core rate limit for our IP/token: {@code {remaining, limit, reset}}, the same way as the stars;
     *  empty until the first fetch. */
    public Map<String, Object> rateLimit() {
        refreshOwnStats();
        Map<String, Object> cached = rateCache;

        return cached != null ? cached : Map.of();
    }

    private void refreshOwnStats() {
        if (System.currentTimeMillis() - ownStatsTs < 60_000 || !ownStatsFetching.compareAndSet(false, true))
            return;

        ownStatsFetch.execute(() -> {
            try {
                fetchStars();
                fetchRateLimit();
            }
            finally {
                ownStatsTs = System.currentTimeMillis();
                ownStatsFetching.set(false);
            }
        });
    }

    @PreDestroy
    void stopOwnStatsFetch() {
        ownStatsFetch.shutdown();
    }

    private void fetchStars() {
        try {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create(props.apiUrl() + "/repos/" + SELF_REPO))
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            RestClient.RequestHeadersSpec<?> r = req;
            Repo repo = recorded("star", () -> r.retrieve().body(Repo.class));
            if (repo != null)
                starCount = repo.stargazersCount();
        }
        catch (Exception e) {
            // keep the last known value (or -1) on any error
        }
    }

    /** Latest release tag of this tool's own repo, without a leading 'v' (cached); null if unavailable. */
    public String latestReleaseTag() {
        long now = System.currentTimeMillis();
        String cached = releaseTag;
        if (cached != null && now - releaseTs < ttlMs)
            return cached;

        try {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create(props.apiUrl() + "/repos/" + SELF_REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            RestClient.RequestHeadersSpec<?> r = req;
            Release release = recorded("release", () -> r.retrieve().body(Release.class));
            if (release != null && release.tagName() != null) {
                releaseTag = release.tagName().replaceFirst("^v", "");
                releaseTs = now;
                return releaseTag;
            }
        }
        catch (Exception e) {
            // keep the last known value (or null) on any error
        }

        return cached;
    }

    /** Uses {@code /rate_limit}, which itself doesn't count against the limit. */
    private void fetchRateLimit() {
        try {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create(props.apiUrl() + "/rate_limit"))
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            Map<?, ?> body = req.retrieve().body(Map.class);
            Object resources = body == null ? null : body.get("resources");
            Object core = resources instanceof Map<?, ?> m ? m.get("core") : null;
            if (core instanceof Map<?, ?> c)
                rateCache = Map.of("remaining", c.get("remaining"), "limit", c.get("limit"), "reset", c.get("reset"));
        }
        catch (Exception e) {
            // keep the last known value (or empty) on any error
        }
    }

    @Override
    public String fileName() {
        return "github.json";
    }

    @Override
    public void saveTo(Path file) throws IOException {
        List<PrSummary> snap = cache;
        if (snap != null && !snap.isEmpty())
            Snapshots.writeAtomic(mapper, file, new Persisted(snap, cacheTs));
    }

    @Override
    public void loadFrom(Path file) throws IOException {
        if (!Files.exists(file))
            return;

        Persisted p = mapper.readValue(file.toFile(), Persisted.class);

        // Restore as-is (keeping the original timestamp): openPrs() then serves this list instantly
        // and only re-fetches once it is older than the TTL, or falls back to it if GitHub is down.
        if (p.prs() != null && !p.prs().isEmpty()) {
            cache = p.prs();
            cacheTs = p.cacheTs();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GhPr(int number, String title, @JsonProperty("html_url") String htmlUrl, GhRef head) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GhRef(String sha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Repo(@JsonProperty("stargazers_count") int stargazersCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Release(@JsonProperty("tag_name") String tagName) {
    }

    private record Persisted(List<PrSummary> prs, long cacheTs) {
    }
}
