package com.github.igniteprchecker.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.igniteprchecker.config.GithubProperties;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Lists open pull requests of the configured GitHub repo, cached briefly to spare the API rate limit. */
@Component
public class GithubClient {
    private static final long TTL_MS = 60_000;

    private final RestClient http = RestClient.create();

    private final GithubProperties props;

    private volatile List<PrSummary> cache;

    private volatile long cacheTs;

    public GithubClient(GithubProperties props) {
        this.props = props;
    }

    /** Open PRs, newest first. Returns the last good result (or empty) if GitHub is unavailable. */
    public List<PrSummary> openPrs() {
        long now = System.currentTimeMillis();
        List<PrSummary> cached = cache;
        if (cached != null && now - cacheTs < TTL_MS)
            return cached;

        try {
            URI uri = URI.create("https://api.github.com/repos/" + props.repo()
                + "/pulls?state=open&sort=created&direction=desc&per_page=50");

            RestClient.RequestHeadersSpec<?> req = http.get()
                .uri(uri)
                .header("Accept", "application/vnd.github+json");

            if (props.token() != null && !props.token().isBlank())
                req = req.header("Authorization", "Bearer " + props.token());

            GhPr[] prs = req.retrieve().body(GhPr[].class);

            List<PrSummary> result = prs == null ? List.of()
                : Arrays.stream(prs).map(p -> new PrSummary(p.number(), p.title(), p.htmlUrl())).toList();

            cache = result;
            cacheTs = now;

            return result;
        }
        catch (Exception e) {
            return cached != null ? cached : List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GhPr(int number, String title, @JsonProperty("html_url") String htmlUrl) {
    }
}
