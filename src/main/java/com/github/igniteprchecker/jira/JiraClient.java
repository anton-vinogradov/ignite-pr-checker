package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.igniteprchecker.metrics.Metrics;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Minimal ASF JIRA (Jira Data Center) client, authenticated with the user's Personal Access Token
 * (Bearer). Used to validate a token and to post the analysis verdict ("visa") as an issue comment.
 */
@Component
public class JiraClient {
    private final RestClient http = RestClient.builder()
        .requestFactory(new SimpleClientHttpRequestFactory())
        .build();

    private final String baseUrl;

    private final Metrics metrics;

    public JiraClient(@Value("${jira.base-url:https://issues.apache.org/jira}") String baseUrl, Metrics metrics) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.metrics = metrics;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Validates a PAT; the JIRA display/user name when the token works. */
    public Optional<String> myself(String token) {
        try {
            Myself me = recorded("myself", () -> http.get().uri(baseUrl + "/rest/api/2/myself")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Myself.class));

            return Optional.ofNullable(me == null ? null : (me.displayName() != null ? me.displayName() : me.name()));
        }
        catch (RestClientResponseException e) {
            return Optional.empty();
        }
    }

    /** The user's JIRA-profile timezone (e.g. {@code Europe/Moscow}) — the only place we can get one. */
    public Optional<String> myTimezone(String token) {
        try {
            Myself me = recorded("myself", () -> http.get().uri(baseUrl + "/rest/api/2/myself")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Myself.class));

            return Optional.ofNullable(me == null ? null : me.timeZone());
        }
        catch (RestClientResponseException e) {
            return Optional.empty();
        }
    }

    /** Posts a comment to the issue; the URL of the created comment. */
    public String addComment(String token, String issueKey, String body) {
        return addCommentWithId(token, issueKey, body).url();
    }

    /** Posts a comment to the issue; its id (for later in-place edits) and browse URL. */
    public PostedComment addCommentWithId(String token, String issueKey, String body) {
        Comment c = recorded("visa", () -> http.post().uri(baseUrl + "/rest/api/2/issue/" + issueKey + "/comment")
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .body(Map.of("body", body))
            .retrieve()
            .body(Comment.class));

        return new PostedComment(c == null ? null : c.id(), baseUrl + "/browse/" + issueKey
            + (c != null && c.id() != null ? "?focusedCommentId=" + c.id() + "#comment-" + c.id() : ""));
    }

    /** Replaces the body of an existing issue comment — the living visa updates in place. */
    public void updateComment(String token, String issueKey, String commentId, String body) {
        recorded("visaEdit", () -> http.put()
            .uri(baseUrl + "/rest/api/2/issue/" + issueKey + "/comment/" + commentId)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .body(Map.of("body", body))
            .retrieve()
            .body(Comment.class));
    }

    public record PostedComment(String id, String url) {
    }

    private <T> T recorded(String category, Supplier<T> call) {
        long t0 = System.nanoTime();
        try {
            T v = call.get();
            metrics.recordJira(category, true, (System.nanoTime() - t0) / 1_000_000L);

            return v;
        }
        catch (RuntimeException e) {
            metrics.recordJira(category, false, (System.nanoTime() - t0) / 1_000_000L);

            throw e;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Myself(String name, String displayName, String timeZone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Comment(String id) {
    }
}
