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
            java.util.Map<?, ?> u = http.get().uri(URI.create("https://api.github.com/user"))
                .header("Authorization", "Bearer " + pat)
                .header("Accept", "application/vnd.github+json")
                .retrieve().body(java.util.Map.class);

            return java.util.Optional.ofNullable(u == null ? null : (String)u.get("login"));
        }
        catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** Posts a comment to the PR under the USER'S OWN PAT; the comment's html url. */
    public String addPrComment(String pat, int prNumber, String body) {
        java.util.Map<?, ?> c = http.post()
            .uri(URI.create("https://api.github.com/repos/" + props.repo() + "/issues/" + prNumber + "/comments"))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class);

        return c == null ? "" : String.valueOf(c.get("html_url"));
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
                : Arrays.stream(prs).map(p -> new PrSummary(p.number(), p.title(), p.htmlUrl(), null, null)).toList();

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
