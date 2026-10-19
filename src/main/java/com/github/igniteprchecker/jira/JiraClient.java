package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.igniteprchecker.config.OutboundHttp;
import com.github.igniteprchecker.metrics.Metrics;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Minimal ASF JIRA (Jira Data Center) client, authenticated with the user's Personal Access Token
 * (Bearer). Used to validate a token and to post the analysis verdict ("visa") as an issue comment.
 */
@Component
public class JiraClient {
    private final RestClient http;

    private final String baseUrl;

    private final Metrics metrics;

    public JiraClient(@Value("${jira.base-url:https://issues.apache.org/jira}") String baseUrl,
        @Value("${jira.read-timeout:60s}") Duration readTimeout, Metrics metrics) {
        this.http = RestClient.builder()
            .requestFactory(OutboundHttp.plain(readTimeout))
            .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.metrics = metrics;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Validates a PAT; the JIRA display/user name when the token works, empty when JIRA refuses it. /myself is the
     * one place to ask: JIRA answers an anonymous caller 401 there, while ASF JIRA serves a dead token on other
     * resources as an anonymous user (JRASERVER-78126). Throws when JIRA could not say: no answer, an outage.
     */
    public Optional<String> myself(String token) {
        try {
            Myself me = recorded("myself", () -> http.get().uri(baseUrl + "/rest/api/2/myself")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Myself.class));

            return Optional.ofNullable(me == null ? null : (me.displayName() != null ? me.displayName() : me.name()));
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401)
                return Optional.empty();

            throw e;
        }
    }

    /**
     * Whether JIRA refuses the token, asked of {@link #myself} after a write failed: the failure itself does not say,
     * as JIRA answers 403 for a ticket the user may not comment on too. False when JIRA could not say.
     */
    public boolean tokenRejected(String token) {
        try {
            return myself(token).isEmpty();
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /** Posts a comment to the issue; its id (for later in-place edits) and browse URL. */
    public PostedComment addCommentWithId(String token, String issueKey, String body) {
        Comment c = recorded("visa", () -> http.post().uri(baseUrl + "/rest/api/2/issue/" + issueKey + "/comment")
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .body(Map.of("body", body))
            .retrieve()
            .body(Comment.class));

        String id = c == null ? null : c.id();

        return new PostedComment(id, commentUrl(issueKey, id));
    }

    /** Where the comment {@code commentId} of the issue opens in the browser; the issue itself without an id. */
    public String commentUrl(String issueKey, String commentId) {
        return baseUrl + "/browse/" + issueKey
            + (commentId != null ? "?focusedCommentId=" + commentId + "#comment-" + commentId : "");
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

    /** The text of an issue comment; empty when the issue has no such comment. */
    public Optional<String> commentBody(String token, String issueKey, String commentId) {
        try {
            Comment c = recorded("visaRead", () -> http.get()
                .uri(baseUrl + "/rest/api/2/issue/" + issueKey + "/comment/" + commentId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Comment.class));

            return Optional.ofNullable(c == null ? null : c.body());
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
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

            throw OutboundHttp.naming("JIRA", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Myself(String name, String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Comment(String id, String body) {
    }
}
