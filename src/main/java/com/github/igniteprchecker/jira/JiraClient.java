package com.github.igniteprchecker.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Optional;
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

    public JiraClient(@Value("${jira.base-url:https://issues.apache.org/jira}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Validates a PAT; the JIRA display/user name when the token works. */
    public Optional<String> myself(String token) {
        try {
            Myself me = http.get().uri(baseUrl + "/rest/api/2/myself")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Myself.class);

            return Optional.ofNullable(me == null ? null : (me.displayName() != null ? me.displayName() : me.name()));
        }
        catch (RestClientResponseException e) {
            return Optional.empty();
        }
    }

    /** Posts a comment to the issue; the URL of the created comment. */
    public String addComment(String token, String issueKey, String body) {
        Comment c = http.post().uri(baseUrl + "/rest/api/2/issue/" + issueKey + "/comment")
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .body(Map.of("body", body))
            .retrieve()
            .body(Comment.class);

        return baseUrl + "/browse/" + issueKey
            + (c != null && c.id() != null ? "?focusedCommentId=" + c.id() + "#comment-" + c.id() : "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Myself(String name, String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Comment(String id) {
    }
}
