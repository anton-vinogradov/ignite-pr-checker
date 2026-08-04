package com.github.igniteprchecker.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.metrics.Metrics;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Lists open pull requests of the configured GitHub repo, cached to stay within the API rate limit. */
@Component
public class GithubClient implements SnapshotCache {
    /** Validates a user's PAT: their GitHub login when the token works. */
    public java.util.Optional<String> ghUser(String pat) {
        try {
            java.util.Map<?, ?> u = recorded("user", () -> http.get().uri(URI.create("https://api.github.com/user"))
                .header("Authorization", "Bearer " + pat)
                .header("Accept", "application/vnd.github+json")
                .retrieve().body(java.util.Map.class));

            return java.util.Optional.ofNullable(u == null ? null : (String)u.get("login"));
        }
        catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** Posts a comment to the PR under the USER'S OWN PAT; its id (for later edits) and html url. */
    public PostedComment addPrComment(String pat, int prNumber, String body) {
        java.util.Map<?, ?> c = recorded("prComment", () -> http.post()
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return c == null ? new PostedComment(0, "")
            : new PostedComment(((Number)c.get("id")).longValue(), String.valueOf(c.get("html_url")));
    }

    /**
     * Posts a PR comment under the APP's own token (the operator's account) — used only for the
     * one-time onboarding reply to a command from a not-yet-enrolled user. False when no app token.
     */
    public boolean addPrCommentAsApp(int prNumber, String body) {
        if (props.token() == null || props.token().isBlank())
            return false;

        recorded("onboard", () -> http.post()
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return true;
    }

    /** Reacts to a comment from the APP's (operator's) account — the ack for PAT-less commanders. */
    public boolean reactToCommentAsApp(long commentId, String content) {
        if (props.token() == null || props.token().isBlank())
            return false;

        recorded("react", () -> http.post()
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/comments/" + commentId + "/reactions"))
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
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return c == null ? null
            : new PostedComment(((Number)c.get("id")).longValue(), String.valueOf(c.get("html_url")));
    }

    /** Edits the APP's own narration comment in place. */
    public void updatePrCommentAsApp(long commentId, String body) {
        recorded("prCommentEdit", () -> http.patch()
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/comments/" + commentId))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));
    }

    /** The PR's author and head (source branch) coordinates — where a style-fix commit must go. */
    public PrHead prHead(int prNumber) {
        java.util.Map<?, ?> pr = recorded("prHead", () -> appGet(
            "https://api.github.com/repos/" + props.repo() + "/pulls/" + prNumber).body(java.util.Map.class));

        java.util.Map<?, ?> user = (java.util.Map<?, ?>)pr.get("user");
        java.util.Map<?, ?> head = (java.util.Map<?, ?>)pr.get("head");
        java.util.Map<?, ?> headRepo = (java.util.Map<?, ?>)head.get("repo");

        return new PrHead((String)user.get("login"), (String)headRepo.get("full_name"),
            (String)head.get("ref"), (String)head.get("sha"));
    }

    /** How many commits {@code head} is ahead of {@code base}, and the head's short sha — for staleness. */
    public Ahead compareAhead(String base, String head) {
        if (base == null || head == null || base.equals(head))
            return new Ahead(0, head == null ? "" : head.substring(0, Math.min(7, head.length())));

        try {
            java.util.Map<?, ?> cmp = recorded("compare", () -> appGet(
                "https://api.github.com/repos/" + props.repo() + "/compare/" + base + "..." + head)
                .body(java.util.Map.class));
            int ahead = cmp == null || cmp.get("ahead_by") == null ? -1 : ((Number)cmp.get("ahead_by")).intValue();

            return new Ahead(ahead, head.substring(0, Math.min(7, head.length())));
        }
        catch (RuntimeException e) {
            // compare can 404 if the base sha was garbage-collected; still flag the mismatch
            return new Ahead(-1, head.substring(0, Math.min(7, head.length())));
        }
    }

    /** Result of a base…head compare: commits ahead ({@code -1} if unknown), and the head short sha. */
    public record Ahead(int ahead, String headShort) {
    }

    /** Paths of the PR's changed (not removed) .java files, capped at 300. */
    public java.util.List<String> prJavaFiles(int prNumber) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            int p = page;
            java.util.List<?> files = recorded("prFiles", () -> appGet(
                "https://api.github.com/repos/" + props.repo() + "/pulls/" + prNumber
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

    /** Raw contents of one file at a ref, from an arbitrary (fork) repo. */
    public String rawFile(String repo, String ref, String path) {
        return recorded("rawFile", () -> {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create("https://api.github.com/repos/" + repo + "/contents/"
                    + path.replace(" ", "%20") + "?ref=" + ref))
                .header("Accept", "application/vnd.github.raw+json");
            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            return req.retrieve().body(String.class);
        });
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
            "https://api.github.com/repos/" + repo + "/git/commits/" + parentSha).body(java.util.Map.class);
        String baseTree = (String)((java.util.Map<?, ?>)parent.get("tree")).get("sha");

        java.util.List<java.util.Map<String, String>> tree = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> f : files.entrySet()) {
            java.util.Map<?, ?> blob = recorded("styleCommit", () -> patPost(pat,
                "https://api.github.com/repos/" + repo + "/git/blobs",
                java.util.Map.of("content", f.getValue(), "encoding", "utf-8")).body(java.util.Map.class));
            tree.add(java.util.Map.of("path", f.getKey(), "mode", "100644", "type", "blob",
                "sha", (String)blob.get("sha")));
        }

        java.util.Map<?, ?> newTree = recorded("styleCommit", () -> patPost(pat,
            "https://api.github.com/repos/" + repo + "/git/trees",
            java.util.Map.of("base_tree", baseTree, "tree", tree)).body(java.util.Map.class));

        java.util.Map<?, ?> commit = recorded("styleCommit", () -> patPost(pat,
            "https://api.github.com/repos/" + repo + "/git/commits",
            java.util.Map.of("message", message, "tree", newTree.get("sha"),
                "parents", java.util.List.of(parentSha))).body(java.util.Map.class));

        String sha = (String)commit.get("sha");
        recorded("styleCommit", () -> http.patch()
            .uri(URI.create("https://api.github.com/repos/" + repo + "/git/refs/heads/" + branch))
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
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/comments/" + commentId))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));
    }

    public record PostedComment(long id, String htmlUrl) {
    }

    /**
     * Issue/PR comments of the whole repo updated since the given instant (ISO-8601), oldest first —
     * ONE call covers every open PR, which is what makes a minute-level command poll affordable.
     */
    public java.util.List<IssueComment> recentIssueComments(String sinceIso) {
        URI uri = URI.create("https://api.github.com/repos/" + props.repo()
            + "/issues/comments?since=" + sinceIso + "&sort=updated&direction=asc&per_page=100");

        RestClient.RequestHeadersSpec<?> req = http.get()
            .uri(uri)
            .header("Accept", "application/vnd.github+json");

        if (props.token() != null && !props.token().isBlank())
            req = req.header("Authorization", "Bearer " + props.token());

        RestClient.RequestHeadersSpec<?> r = req;
        IssueComment[] comments = recorded("comments", () -> r.retrieve().body(IssueComment[].class));

        return comments == null ? java.util.List.of() : java.util.List.of(comments);
    }

    /** Reacts to an issue/PR comment under the USER'S OWN PAT (content: rocket, confused, ...). */
    public void reactToComment(String pat, long commentId, String content) {
        recorded("react", () -> http.post()
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/comments/" + commentId + "/reactions"))
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

    /** This tool's own repo, for the "Star" button (fetched server-side so browser blockers don't hide it). */
    private static final String SELF_REPO = "anton-vinogradov/ignite-pr-checker";

    private final RestClient http = RestClient.create();

    private final GithubProperties props;
    private final long ttlMs;
    private final ObjectMapper mapper;

    private volatile List<PrSummary> cache;

    private volatile long cacheTs;

    private volatile int starCount = -1;

    private volatile long starTs;

    private volatile String releaseTag;

    private volatile long releaseTs;

    private volatile Map<String, Object> rateCache;

    private volatile long rateTs;

    private final Metrics metrics;

    public GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics) {
        this.props = props;
        this.ttlMs = props.cacheSeconds() * 1000L;
        this.mapper = mapper;
        this.metrics = metrics;
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
            throw e;
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
            URI uri = URI.create("https://api.github.com/repos/" + props.repo()
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
                : Arrays.stream(prs).map(p -> new PrSummary(p.number(), p.title(), p.htmlUrl(), null, null, null)).toList();

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

    /** Number of open PRs currently cached (for the status page). */
    public int prCount() {
        List<PrSummary> c = cache;
        return c == null ? 0 : c.size();
    }

    /** Star count of this tool's own repo (cached ~1 min so it reflects new stars quickly); -1 if unavailable yet. */
    public int starCount() {
        long now = System.currentTimeMillis();
        int cached = starCount;
        if (cached >= 0 && now - starTs < 60_000)
            return cached;

        try {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create("https://api.github.com/repos/" + SELF_REPO))
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            RestClient.RequestHeadersSpec<?> r = req;
            Repo repo = recorded("star", () -> r.retrieve().body(Repo.class));
            if (repo != null) {
                starCount = repo.stargazersCount();
                starTs = now;
                return starCount;
            }
        }
        catch (Exception e) {
            // keep the last known value (or -1) on any error
        }

        return cached;
    }

    /** Latest release tag of this tool's own repo, without a leading 'v' (cached); null if unavailable. */
    public String latestReleaseTag() {
        long now = System.currentTimeMillis();
        String cached = releaseTag;
        if (cached != null && now - releaseTs < ttlMs)
            return cached;

        try {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create("https://api.github.com/repos/" + SELF_REPO + "/releases/latest"))
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

    /** GitHub API core rate limit for our IP/token: {@code {remaining, limit, reset}} (cached ~1 min);
     *  empty if unavailable. Uses {@code /rate_limit}, which itself doesn't count against the limit. */
    public Map<String, Object> rateLimit() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = rateCache;
        if (cached != null && now - rateTs < 60_000)
            return cached;

        try {
            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(URI.create("https://api.github.com/rate_limit"))
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            Map<?, ?> body = req.retrieve().body(Map.class);
            Object resources = body == null ? null : body.get("resources");
            Object core = resources instanceof Map<?, ?> m ? m.get("core") : null;
            if (core instanceof Map<?, ?> c) {
                rateCache = Map.of("remaining", c.get("remaining"), "limit", c.get("limit"), "reset", c.get("reset"));
                rateTs = now;
                return rateCache;
            }
        }
        catch (Exception e) {
            // keep the last known value (or empty) on any error
        }

        return cached != null ? cached : Map.of();
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
    private record GhPr(int number, String title, @JsonProperty("html_url") String htmlUrl) {
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
