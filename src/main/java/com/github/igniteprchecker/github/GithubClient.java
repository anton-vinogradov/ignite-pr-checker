package com.github.igniteprchecker.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.GithubProperties;
import com.github.igniteprchecker.persist.SnapshotCache;
import com.github.igniteprchecker.persist.Snapshots;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Lists open pull requests of the configured GitHub repo, cached to stay within the API rate limit. */
@Component
public class GithubClient implements SnapshotCache {
    private final RestClient http = RestClient.create();

    private final GithubProperties props;
    private final long ttlMs;
    private final ObjectMapper mapper;

    private volatile List<PrSummary> cache;

    private volatile long cacheTs;

    public GithubClient(GithubProperties props, ObjectMapper mapper) {
        this.props = props;
        this.ttlMs = props.cacheSeconds() * 1000L;
        this.mapper = mapper;
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

            GhPr[] prs = req.retrieve().body(GhPr[].class);

            List<PrSummary> result = prs == null ? List.of()
                : Arrays.stream(prs).map(p -> new PrSummary(p.number(), p.title(), p.htmlUrl())).toList();

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

    private record Persisted(List<PrSummary> prs, long cacheTs) {
    }
}
