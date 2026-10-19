package com.github.igniteprchecker.github;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

/** Lists open pull requests of the configured GitHub repo, cached to stay within the API rate limit. */
@Component
public class GithubClient implements SnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(GithubClient.class);

    /**
     * Validates a user's token: the GitHub account it belongs to, by login and id, when the token works; empty when
     * GitHub refuses it. A token with no scopes or permissions at all works: GitHub names its owner to any token.
     * Throws when GitHub could not say: no answer, an outage, a rate limit.
     */
    public java.util.Optional<GhUser> ghIdentity(String pat) {
        return tokenOwner(pat).map(TokenOwner::user);
    }

    /**
     * Whose a user's token is, and its scopes, as {@link #ghIdentity} asks: GitHub names the scopes of a classic token
     * with every answer, none of a fine-grained one.
     */
    public java.util.Optional<TokenOwner> tokenOwner(String pat) {
        try {
            ResponseEntity<GhUser> answer = recordedAsUser("user", () -> http.get()
                .uri(URI.create(props.apiUrl() + "/user"))
                .header("Authorization", "Bearer " + pat)
                .header("Accept", "application/vnd.github+json")
                .retrieve().toEntity(GhUser.class));
            GhUser u = answer.getBody();
            if (u == null || u.login() == null)
                return java.util.Optional.empty();

            String scopes = answer.getHeaders().getFirst(AppAccountChecks.SCOPES);

            return java.util.Optional.of(new TokenOwner(u, scopes == null ? null : Arrays.stream(scopes.split(","))
                .map(String::strip).filter(x -> !x.isEmpty()).toList(),
                answer.getHeaders().getFirst(AppAccountChecks.EXPIRATION)));
        }
        catch (RuntimeException e) {
            if (refusesToken(e))
                return java.util.Optional.empty();

            throw e;
        }
    }

    /**
     * The account a token belongs to, and the scopes of a classic token; null {@code scopes}: GitHub named none.
     * {@code expiration}: when the token expires, as GitHub wrote it; null when the token never expires.
     */
    public record TokenOwner(GhUser user, List<String> scopes, String expiration) {
        /** The owner of a token that never expires. */
        public TokenOwner(GhUser user, List<String> scopes) {
            this(user, scopes, null);
        }

        /** When the token expires; empty when it never does or when GitHub wrote a date the checker cannot read. */
        public Optional<Instant> expiresAt() {
            return expiration == null ? Optional.empty() : Optional.ofNullable(AppAccountChecks.expiresAt(expiration));
        }
    }

    /**
     * Whether users can link their GitHub accounts through the checker's OAuth App: its client id and secret are set,
     * and the checker has a public address for GitHub to send the user back to.
     */
    public boolean linksAccounts() {
        return props.oauthClientId() != null && props.oauthClientSecret() != null && linkCallback != null;
    }

    /**
     * Where GitHub asks the user to let the checker's OAuth App see who they are. No scope: the app gets the public
     * profile and nothing more. No sign-up on the way. GitHub sends the user back to {@link #LINK_CALLBACK} with
     * {@code state} and a code.
     */
    public URI authorizeUri(String state) {
        return UriComponentsBuilder.fromUriString(props.webUrl() + "/login/oauth/authorize")
            .queryParam("client_id", props.oauthClientId())
            .queryParam("redirect_uri", linkCallback)
            .queryParam("state", state)
            .queryParam("allow_signup", "false")
            .encode().build().toUri();
    }

    /**
     * The GitHub account that let the checker's OAuth App see it, by the code GitHub sent back with the user. The code
     * buys a token, the token names its account, and the token is revoked at once: it goes into no field, file or log.
     * Empty when GitHub gives no token for the code: it expired, was used, or the app's settings do not match. A
     * failed revocation is logged and changes nothing, as a token with no scope reads only what is public anyway.
     * Throws when GitHub could not say.
     */
    public Optional<GhUser> oauthUser(String code) {
        Optional<String> token = oauthToken(code);
        if (token.isEmpty())
            return Optional.empty();

        try {
            return ghIdentity(token.get());
        }
        finally {
            revokeOauthToken(token.get());
        }
    }

    private Optional<String> oauthToken(String code) {
        byte[] form = ("client_id=" + formValue(props.oauthClientId())
            + "&client_secret=" + formValue(props.oauthClientSecret())
            + "&code=" + formValue(code)
            + "&redirect_uri=" + formValue(linkCallback)).getBytes(StandardCharsets.UTF_8);
        Map<?, ?> answer = recordedAsUser("oauth", () -> http.post()
            .uri(URI.create(props.webUrl() + "/login/oauth/access_token"))
            .header("Accept", "application/json")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .contentLength(form.length)
            .body(unlogged(form))
            .retrieve().body(Map.class));
        if (answer != null && answer.get("access_token") instanceof String token && !token.isBlank())
            return Optional.of(token);

        log.warn("GitHub gave no token for the code of a GitHub account link: {}",
            answer == null ? "no answer" : answer.get("error"));

        return Optional.empty();
    }

    private void revokeOauthToken(String token) {
        try {
            byte[] json = mapper.writeValueAsBytes(Map.of("access_token", token));
            recordedAsUser("oauthRevoke", () -> http.method(HttpMethod.DELETE)
                .uri(URI.create(props.apiUrl() + "/applications/" + props.oauthClientId() + "/token"))
                .headers(h -> h.setBasicAuth(props.oauthClientId(), props.oauthClientSecret()))
                .header("Accept", "application/vnd.github+json")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(json.length)
                .body(unlogged(json))
                .retrieve().toBodilessEntity());
        }
        catch (RuntimeException e) {
            log.warn("GitHub did not revoke the token of a GitHub account link: {}", e.toString());
        }
    }

    private static String formValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * A request body written as it is. Spring writes a body it converts into the log at DEBUG, and these bodies carry
     * the OAuth App's client secret or a user's token.
     */
    private static StreamingHttpOutputMessage.Body unlogged(byte[] body) {
        return out -> out.write(body);
    }

    /**
     * Whether GitHub refused the token of the failed call itself: 401, "Bad credentials". A 403 is a rate limit or a
     * resource the token may not touch, and says nothing of the token.
     */
    public static boolean refusesToken(Throwable e) {
        return e instanceof RestClientResponseException rest && rest.getStatusCode().value() == 401;
    }

    /** Whether GitHub refused the failed call for its rate limits: a 429, or a 403 that says so. */
    public static boolean rateLimited(Throwable e) {
        return e instanceof RestClientResponseException rest && AppWrites.waitUntil(rest, 0, 0) != null;
    }

    /**
     * Whether a GitHub account is the app account, the one {@code GITHUB_TOKEN} belongs to: by its id once GitHub
     * named it, else by its login. False while GitHub has not said whose the token is.
     */
    public boolean isAppAccount(GhUser who) {
        AppAccount app = appAccount;
        if (who == null || app.login() == null)
            return false;

        return app.id() != null && who.id() > 0 ? app.id() == who.id() : app.login().equalsIgnoreCase(who.login());
    }

    /** What GitHub allows in a login: letters, digits and single hyphens inside, up to 39 characters. */
    private static final java.util.regex.Pattern LOGIN =
        java.util.regex.Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}");

    /**
     * Posts a comment to the PR from the app account ({@link #appAccount()}), for {@code user}, whose writes it counts
     * among (see {@link AppWrites}): its id, for later edits, and html url. Null without {@code GITHUB_TOKEN}. Throws
     * {@link WriteDeferred} when the write is to wait, else GitHub's own refusal.
     */
    public PostedComment appComment(int pr, String user, String body) {
        return appComment(props.repo(), pr, user, body);
    }

    /** The same in a PR of {@code repo}, such as the Python thin client's. */
    public PostedComment appComment(String repo, int pr, String user, String body) {
        java.util.Map<?, ?> c = appWrite("prComment", writeKey(repo, pr), user, () -> http.post()
            .uri(URI.create(props.apiUrl() + "/repos/" + repo + "/issues/" + pr + "/comments"))
            .header("Authorization", "Bearer " + props.token())
            .header("Accept", "application/vnd.github+json")
            .body(java.util.Map.of("body", body))
            .retrieve().body(java.util.Map.class));

        return c == null ? null
            : new PostedComment(((Number)c.get("id")).longValue(), String.valueOf(c.get("html_url")));
    }

    /** Edits a comment of the app account in the PR, as {@link #appComment} posts one. False without the token. */
    public boolean appEdit(int pr, String user, long commentId, String body) {
        return appEdit(props.repo(), pr, user, commentId, body);
    }

    /** The same in a PR of {@code repo}. */
    public boolean appEdit(String repo, int pr, String user, long commentId, String body) {
        return appWrite("prCommentEdit", writeKey(repo, pr), user, () -> {
            http.patch()
                .uri(URI.create(props.apiUrl() + "/repos/" + repo + "/issues/comments/" + commentId))
                .header("Authorization", "Bearer " + props.token())
                .header("Accept", "application/vnd.github+json")
                .body(java.util.Map.of("body", body))
                .retrieve().toBodilessEntity();

            return Boolean.TRUE;
        }) != null;
    }

    /** Reacts to a comment of the PR from the app account (content: rocket, confused, ...). False without the token. */
    public boolean appReact(int pr, String user, long commentId, String content) {
        return appReact(props.repo(), pr, user, commentId, content);
    }

    /** The same in a PR of {@code repo}. */
    public boolean appReact(String repo, int pr, String user, long commentId, String content) {
        return appWrite("react", writeKey(repo, pr), user, () -> {
            http.post()
                .uri(URI.create(props.apiUrl() + "/repos/" + repo + "/issues/comments/" + commentId
                    + "/reactions"))
                .header("Authorization", "Bearer " + props.token())
                .header("Accept", "application/vnd.github+json")
                .body(java.util.Map.of("content", content))
                .retrieve().toBodilessEntity();

            return Boolean.TRUE;
        }) != null;
    }

    /**
     * Deletes a comment of the app account in the PR. True when it is gone, also when GitHub had it no longer; false
     * without the token.
     */
    public boolean appDelete(int pr, String user, long commentId) {
        return appDelete(props.repo(), pr, user, commentId);
    }

    /** The same in a PR of {@code repo}. */
    public boolean appDelete(String repo, int pr, String user, long commentId) {
        try {
            return appWrite("prCommentDelete", writeKey(repo, pr), user, () -> {
                http.delete()
                    .uri(URI.create(props.apiUrl() + "/repos/" + repo + "/issues/comments/" + commentId))
                    .header("Authorization", "Bearer " + props.token())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve().toBodilessEntity();

                return Boolean.TRUE;
            }) != null;
        }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return true;
        }
    }

    /** Whether the checker has an account to write to GitHub with: {@code GITHUB_TOKEN} is set. */
    public boolean writes() {
        return props.token() != null && !props.token().isBlank();
    }

    /**
     * Whether a write of the app account may go now: the token is set, GitHub has not refused it, and GitHub did not
     * ask to wait. A cap of {@link AppWrites} may still defer it.
     */
    public boolean canWrite() {
        return writes() && !appAccount.refused() && !appWrites.paused(System.currentTimeMillis());
    }

    /** What the status page and the service health show of the app account's writes. */
    public WriteState writeState() {
        return appWrites.state(System.currentTimeMillis());
    }

    /**
     * A write of the app account, admitted by {@link AppWrites} and recorded like any app call. Null without the
     * token. A refusal that asks to wait pauses the writes and comes back as {@link WriteDeferred}.
     */
    private <T> T appWrite(String category, String pr, String user, Supplier<T> call) {
        if (!writes())
            return null;

        long now = System.currentTimeMillis();
        appWrites.admit(pr, user, now);
        appWrites.sent(pr, user, now);
        try {
            T result = recorded(category, call);
            appWrites.worked();

            return result;
        }
        catch (RestClientResponseException e) {
            if (!appWrites.limited(e, System.currentTimeMillis()))
                throw e;

            log.warn("GitHub rate-limits the writes of the app account ({} on a {} call): they wait until {}",
                e.getStatusCode().value(), category, java.time.Instant.ofEpochMilli(writeState().pausedUntil()));

            throw new WriteDeferred("GitHub answered " + e.getStatusCode().value() + " to a " + category + " call");
        }
    }

    /**
     * A write of the app account that was not made now: GitHub asked to wait, or the writes to its PR or for its user
     * reached their hourly cap. Nothing went to GitHub, or GitHub took nothing; the caller tries again later.
     */
    public static final class WriteDeferred extends RuntimeException {
        public WriteDeferred(String why) {
            super(why);
        }
    }

    /**
     * The app account's writes now: {@code pausedUntil}, the time GitHub asked them to wait until (epoch ms, 0 when it
     * did not); what GitHub {@code refused} a write with last for another reason than its limits, and when, null since
     * a write went in; who ran into a cap of writes in the last hour ("PR 13800", "user alice").
     */
    public record WriteState(long pausedUntil, String refused, long refusedAt, List<String> capped) {
        /** What anyone may see: whether writes wait or fail and for which PRs, not GitHub's words or whose writes. */
        public WriteState anonymous() {
            return new WriteState(pausedUntil, null, refusedAt,
                capped.stream().map(c -> c.startsWith("PR ") ? c : "a user").distinct().toList());
        }
    }

    private final AppWrites appWrites = new AppWrites();

    /**
     * What the writes to a PR are counted by: its number in the repo the checker lists, as before other repos came,
     * and "repo#number" in another, whose PR numbers overlap.
     */
    private String writeKey(String repo, int pr) {
        return repo.equals(props.repo()) ? String.valueOf(pr) : repo + "#" + pr;
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

    /**
     * The sha of the head commit of a PR of {@code repo}; null when GitHub names none. Holds for a PR whose fork was
     * deleted, as {@link #prHeadSha(int)} does.
     */
    public String prHeadSha(String repo, int prNumber) {
        java.util.Map<?, ?> pr = recorded("prHead", () -> appGet(
            props.apiUrl() + "/repos/" + repo + "/pulls/" + prNumber).body(java.util.Map.class));
        Object head = pr == null ? null : pr.get("head");

        return head instanceof java.util.Map<?, ?> h ? (String)h.get("sha") : null;
    }

    /** The sha of the newest commit of a branch of {@code repo}, such as master; null when GitHub names none. */
    public String branchHeadSha(String repo, String branch) {
        java.util.Map<?, ?> ref = recorded("branchHead", () -> appGet(props.apiUrl() + "/repos/" + repo
            + "/git/ref/heads/" + UriUtils.encodePath(branch, StandardCharsets.UTF_8)).body(java.util.Map.class));
        Object commit = ref == null ? null : ref.get("object");

        return commit instanceof java.util.Map<?, ?> c ? (String)c.get("sha") : null;
    }

    /** Whether a PR is still open, and its title; empty when GitHub has no such PR. */
    public java.util.Optional<PullState> pullState(int prNumber) {
        return pullState(props.repo(), prNumber);
    }

    /** The same for a PR of {@code repo}, such as the Python thin client's. */
    public java.util.Optional<PullState> pullState(String repo, int prNumber) {
        try {
            java.util.Map<?, ?> pr = recorded("prState", () -> appGet(
                props.apiUrl() + "/repos/" + repo + "/pulls/" + prNumber).body(java.util.Map.class));

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

    /**
     * The sha of the PR's head commit; null when GitHub names none. Unlike {@link #prHead} it holds for a PR whose
     * fork was deleted: GitHub then gives the head no repository, but still its sha.
     */
    public String prHeadSha(int prNumber) {
        java.util.Map<?, ?> pr = recorded("prHead", () -> appGet(
            props.apiUrl() + "/repos/" + props.repo() + "/pulls/" + prNumber).body(java.util.Map.class));
        Object head = pr == null ? null : pr.get("head");

        return head instanceof java.util.Map<?, ?> h ? (String)h.get("sha") : null;
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

    /**
     * The check suites GitHub shows for a commit, up to 100. Each workflow run of GitHub Actions is one, and so is a
     * run that waits for a maintainer to approve it: that one has no check yet.
     */
    public java.util.List<CheckSuite> checkSuites(String sha) {
        CheckSuites suites = recorded("checkSuites", () -> appGet(
            props.apiUrl() + "/repos/" + props.repo() + "/commits/" + sha + "/check-suites?per_page=100")
            .body(CheckSuites.class));

        return suites == null || suites.checkSuites() == null ? java.util.List.of() : suites.checkSuites();
    }

    /** A check suite of a commit: the app it belongs to, GitHub's {@code status} and {@code conclusion}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckSuite(long id, String status, String conclusion, App app) {
        /** The app by its slug: "github-actions" for workflow runs. */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record App(String slug) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckSuites(@JsonProperty("check_suites") java.util.List<CheckSuite> checkSuites) {
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

        return Optional.ofNullable(timed("jobLog", () -> http.get().uri(first.at())
            .exchange((request, response) -> readLog(response, reader, jobId)), false));
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
        RestClient.RequestHeadersSpec<?> req = http.get()
            .uri(contentsUri(repo, ref, path))
            .header("Accept", "application/vnd.github.raw+json");
        if (props.token() != null && !props.token().isBlank())
            req = req.header("Authorization", "Bearer " + props.token());

        return req;
    }

    /** Where GitHub gives a file, or a directory's list, of a repo at a ref. */
    private URI contentsUri(String repo, String ref, String path) {
        String encoded = Arrays.stream(path.split("/", -1))
            .map(seg -> UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("/"));

        return URI.create(props.apiUrl() + "/repos/" + repo + "/contents/" + encoded + "?ref="
            + UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8));
    }

    /**
     * The files under directory {@code dir} of {@code repo} at {@code ref}, by path from the repo's root, with their
     * sizes. Two calls: the parent directory, which names the directory's tree, and that tree.
     */
    public Tree filesUnder(String repo, String ref, String dir) {
        int slash = dir.lastIndexOf('/');
        String name = dir.substring(slash + 1);
        List<?> entries = recorded("tree", () -> appGet(contentsUri(repo, ref,
            slash < 0 ? "" : dir.substring(0, slash)).toString()).body(List.class));
        Optional<String> sha = entries == null ? Optional.empty() : entries.stream()
            .filter(e -> e instanceof Map<?, ?> m && name.equals(m.get("name")) && "dir".equals(m.get("type")))
            .map(e -> String.valueOf(((Map<?, ?>)e).get("sha"))).findFirst();
        if (sha.isEmpty())
            return new Tree(List.of(), false);

        GitTree tree = recorded("tree", () -> appGet(props.apiUrl() + "/repos/" + repo + "/git/trees/" + sha.get()
            + "?recursive=1").body(GitTree.class));
        if (tree == null || tree.tree() == null)
            throw new IllegalStateException("GitHub sent no tree of " + dir);

        return new Tree(tree.tree().stream().filter(e -> "blob".equals(e.type()))
            .map(e -> new Tree.File(dir + "/" + e.path(), e.size() == null ? 0 : e.size())).toList(), tree.truncated());
    }

    /** Files of a directory, with their sizes in bytes. {@code truncated}: GitHub cut the list short. */
    public record Tree(List<File> files, boolean truncated) {
        /** A file by its path from the repo's root. */
        public record File(String path, long size) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GitTree(List<Entry> tree, boolean truncated) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Entry(String path, String type, Long size) {
        }
    }

    /** The open PRs of a GitHub user in the repo, by number, up to 100: one search call. */
    public List<Integer> openPrsBy(String login) {
        if (login == null || !LOGIN.matcher(login).matches())
            return List.of();

        String q = UriUtils.encodeQueryParam("repo:" + props.repo() + " is:pr is:open author:" + login,
            StandardCharsets.UTF_8);
        Found found = recorded("prSearch", () -> appGet(props.apiUrl() + "/search/issues?q=" + q
            + "&per_page=100").body(Found.class));

        return found == null || found.items() == null ? List.of()
            : found.items().stream().map(Found.Item::number).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Found(List<Item> items) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Item(int number) {
        }
    }

    /**
     * Creates ONE commit updating the given files on a branch — pure Git Data API, no clone: blobs ->
     * tree (on top of the parent's) -> commit -> ref. Runs under the USER'S OWN PAT (their fork,
     * their branch, their authorship). Returns the new commit sha; fails if the branch moved away
     * from {@code parentSha} (never force-pushes over someone's newer work).
     */
    public String commitFiles(String pat, String repo, String branch, String parentSha,
        java.util.Map<String, String> files, String message) {
        java.util.Map<?, ?> parent = recordedAsUser("commit", () -> patGet(pat,
            props.apiUrl() + "/repos/" + repo + "/git/commits/" + parentSha).body(java.util.Map.class));
        String baseTree = (String)((java.util.Map<?, ?>)parent.get("tree")).get("sha");

        java.util.List<java.util.Map<String, String>> tree = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> f : files.entrySet()) {
            java.util.Map<?, ?> blob = recordedAsUser("commit", () -> patPost(pat,
                props.apiUrl() + "/repos/" + repo + "/git/blobs",
                java.util.Map.of("content", f.getValue(), "encoding", "utf-8")).body(java.util.Map.class));
            tree.add(java.util.Map.of("path", f.getKey(), "mode", "100644", "type", "blob",
                "sha", (String)blob.get("sha")));
        }

        java.util.Map<?, ?> newTree = recordedAsUser("commit", () -> patPost(pat,
            props.apiUrl() + "/repos/" + repo + "/git/trees",
            java.util.Map.of("base_tree", baseTree, "tree", tree)).body(java.util.Map.class));

        java.util.Map<?, ?> commit = recordedAsUser("commit", () -> patPost(pat,
            props.apiUrl() + "/repos/" + repo + "/git/commits",
            java.util.Map.of("message", message, "tree", newTree.get("sha"),
                "parents", java.util.List.of(parentSha))).body(java.util.Map.class));

        String sha = (String)commit.get("sha");
        recordedAsUser("commit", () -> http.patch()
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

    /** The repo whose open PRs the checker lists and checks: {@code GITHUB_REPO}. */
    public String repo() {
        return props.repo();
    }

    /**
     * Issue/PR comments of the whole repo updated since the given instant (ISO-8601), oldest first —
     * ONE call covers every open PR, which is what makes a minute-level command poll affordable. A
     * full page means there is more: the next pages are read too, up to {@link #COMMENT_PAGES}.
     */
    public java.util.List<IssueComment> recentIssueComments(String sinceIso) {
        return recentIssueComments(props.repo(), sinceIso);
    }

    /** The same in {@code repo}, such as the Python thin client's. */
    public java.util.List<IssueComment> recentIssueComments(String repo, String sinceIso) {
        java.util.List<IssueComment> out = new java.util.ArrayList<>();
        for (int page = 1; page <= COMMENT_PAGES; page++) {
            URI uri = URI.create(props.apiUrl() + "/repos/" + repo + "/issues/comments?since="
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

    /**
     * One repo comment from the poll: {@code htmlUrl} tells a PR comment from a plain issue's. {@code updatedAt} is
     * {@code createdAt} until someone edits the comment; null when GitHub does not say.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssueComment(long id, String body,
        @JsonProperty("html_url") String htmlUrl, @JsonProperty("created_at") String createdAt, GhUser user,
        @JsonProperty("updated_at") String updatedAt) {
        /** A comment with no word of an edit. */
        public IssueComment(long id, String body, String htmlUrl, String createdAt, GhUser user) {
            this(id, body, htmlUrl, createdAt, user, null);
        }
    }

    /** A GitHub account: its login, which its owner may change, and its id, which never changes (0: not known). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GhUser(String login, long id) {
    }

    /**
     * This tool's own repo: its stars for the "Star" button (fetched server-side so browser blockers don't hide it)
     * and its releases.
     */
    public static final String SELF_REPO = "anton-vinogradov/ignite-pr-checker";

    /**
     * The checker's page GitHub sends a user back to from Link GitHub. The operator registers {@code APP_PUBLIC_URL} +
     * this as the OAuth App's callback URL.
     */
    public static final String LINK_CALLBACK = "/api/github/callback";

    private final RestClient http;

    /** For calls whose redirect leads to another host: it is followed by hand, without the token. */
    private final RestClient noRedirects;

    private final GithubProperties props;

    /**
     * Where GitHub sends a user back from the checker's OAuth App: the checker's public address and
     * {@link #LINK_CALLBACK}. Null when the checker has no public address.
     */
    private final String linkCallback;

    private final long ttlMs;
    private final ObjectMapper mapper;

    private volatile List<PrSummary> cache;

    private volatile long cacheTs;

    private volatile int starCount = -1;

    private volatile String releaseTag;

    private volatile long releaseTs;

    private volatile Map<String, Object> rateCache;

    /** See {@link #appQuota}. */
    private final AtomicReference<Quota> appQuota = new AtomicReference<>();

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

    /** A client of the checker at {@code publicUrl}, the address users open ({@code APP_PUBLIC_URL}). */
    @Autowired
    public GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics,
        @Value("${app.public-url:}") String publicUrl) {
        this(props, mapper, metrics, publicUrl, RestClient.builder()
            .requestFactory(OutboundHttp.withPatch(props.readTimeout()))
            .configureMessageConverters(OutboundHttp::json)
            .build(), RestClient.builder()
            .requestFactory(OutboundHttp.withPatchNoRedirects(props.readTimeout()))
            .configureMessageConverters(OutboundHttp::json)
            .build());
    }

    /** A client of a checker with no public address: nobody links a GitHub account through it. */
    public GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics) {
        this(props, mapper, metrics, "");
    }

    /** A client whose calls all go through {@code http}, which hands a redirect back as it is: a test's stub. */
    GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics, RestClient http) {
        this(props, mapper, metrics, "", http, http);
    }

    /** A test's stub, as above, of the checker at {@code publicUrl}. */
    GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics, String publicUrl, RestClient http) {
        this(props, mapper, metrics, publicUrl, http, http);
    }

    private GithubClient(GithubProperties props, ObjectMapper mapper, Metrics metrics, String publicUrl,
        RestClient http, RestClient noRedirects) {
        this.props = props;
        this.ttlMs = props.cacheSeconds() * 1000L;
        this.mapper = mapper;
        this.metrics = metrics;
        this.http = http.mutate().requestInterceptor(this::noteQuota).build();
        this.noRedirects = noRedirects.mutate().requestInterceptor(this::noteQuota).build();
        String base = publicUrl == null ? "" : publicUrl.strip().replaceFirst("/+$", "");
        this.linkCallback = base.isEmpty() ? null : base + LINK_CALLBACK;
    }

    /**
     * Runs a GitHub call under the app token (or none), recording its category, outcome and latency for the status
     * page. GitHub refusing the app token marks the app account refused; a call that works again brings it back.
     */
    private <T> T recorded(String category, Supplier<T> call) {
        return timed(category, call, true);
    }

    /**
     * Runs a GitHub call under a user's own token. It is recorded apart from the app token's calls, under
     * {@link #USER_CALL} and its category, and its refusal says nothing of the app token.
     */
    private <T> T recordedAsUser(String category, Supplier<T> call) {
        return timed(USER_CALL + category, call, false);
    }

    /** What the categories of calls under users' own tokens start with on the status page. */
    private static final String USER_CALL = "pat:";

    /** Runs and records the call; {@code appToken}: it carries the app token, so its outcome tells of that token. */
    private <T> T timed(String category, Supplier<T> call, boolean appToken) {
        long t0 = System.nanoTime();
        try {
            T result = call.get();
            metrics.recordGithub(category, true, (System.nanoTime() - t0) / 1_000_000L);
            if (appToken)
                appTokenWorked();

            return result;
        }
        catch (RuntimeException e) {
            metrics.recordGithub(category, false, (System.nanoTime() - t0) / 1_000_000L);
            if (appToken && refusesToken(e))
                appTokenRefused(category);

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
     * The PR's head as the open-PR list last fetched it, when that was at or after {@code sinceEpochSec}; null when
     * the PR is not on it or the list is older. Never calls GitHub.
     */
    public String listedHead(int prNumber, long sinceEpochSec) {
        long listedAt = cacheTs;
        List<PrSummary> listed = cache;
        if (listed == null || listedAt < sinceEpochSec * 1000L)
            return null;

        return listed.stream().filter(p -> p.number() == prNumber).map(PrSummary::headSha).filter(Objects::nonNull)
            .findFirst().orElse(null);
    }

    /**
     * The GitHub account the checker writes as, the one {@code GITHUB_TOKEN} belongs to: every comment, edit and
     * reaction of the checker in the repo comes from it. Checked once the service is up and every 6 hours; while
     * GitHub could not say, asked again at most every 10 minutes.
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

    /** A token nears its expiry, and its account gets or loses rights, while the service runs. */
    @Scheduled(fixedDelay = APP_ACCOUNT_REFRESH_MS, initialDelay = APP_ACCOUNT_REFRESH_MS)
    void recheckAppAccount() {
        ownStatsFetch.execute(this::checkAppAccount);
    }

    /**
     * Asks GitHub whom the app token belongs to, what kind of token it is and when it expires, and whether that
     * account may push to the repo and to the checker's own.
     */
    void checkAppAccount() {
        appAccountCheckedAt = System.currentTimeMillis();
        if (props.token() == null || props.token().isBlank()) {
            appAccount = new AppAccount(AppAccount.NONE, null, null);
            log.info("no GITHUB_TOKEN: GitHub answers at most 60 calls an hour, and the checker has no account to "
                + "write with: the PR comment, PR commands, the checkstyle autofix and tests to suites are off");

            return;
        }

        ResponseEntity<?> user;
        try {
            user = timed("appUser", () -> appGet(props.apiUrl() + "/user").toEntity(java.util.Map.class), false);
        }
        catch (org.springframework.web.client.RestClientResponseException e) {
            AppAccount was = appAccount;
            appAccount = refusesToken(e)
                ? new AppAccount(AppAccount.REFUSED, was.login(), was.canPush(), was.id(), List.of()) : stillKnown(was);
            log.warn("GitHub answered {} when asked whom GITHUB_TOKEN belongs to: {}", e.getStatusCode().value(),
                refusesToken(e) ? "the token is refused, the checker writes nothing to GitHub" : "asked again later");

            return;
        }
        catch (RuntimeException e) {
            appAccount = stillKnown(appAccount);
            log.warn("could not ask GitHub whom GITHUB_TOKEN belongs to, asked again later: {}", e.toString());

            return;
        }

        java.util.Map<?, ?> u = user.getBody() instanceof java.util.Map<?, ?> body ? body : null;
        String login = u == null ? null : (String)u.get("login");
        Long id = u != null && u.get("id") instanceof Number n ? n.longValue() : null;
        Boolean canPush = canPush(props.repo());
        List<AppAccount.Check> checks = AppAccountChecks.of(props.token(), user.getHeaders(), props.repo(), canPush,
            canPush(SELF_REPO), System.currentTimeMillis());
        AppAccount was = appAccount;
        appAccount = new AppAccount(AppAccount.OK, login, canPush, id, checks);
        if (!AppAccount.OK.equals(was.state()) || !Objects.equals(was.login(), login) || !was.checks().equals(checks))
            logChecks(login, checks);
    }

    /**
     * The app account when GitHub could not be asked: what GitHub said of it last, as a check every 6 hours that
     * failed once does not make a known account unknown.
     */
    private static AppAccount stillKnown(AppAccount was) {
        return AppAccount.OK.equals(was.state()) ? was : new AppAccount(AppAccount.UNKNOWN, null, null);
    }

    /** What was found of the token, when it is news: the operator reads it in the log too. */
    private static void logChecks(String login, List<AppAccount.Check> checks) {
        checks.stream().filter(c -> c.level().equals(AppAccount.Check.WARN) || c.level().equals(AppAccount.Check.ERROR))
            .forEach(c -> log.warn("GitHub app account @{}: {}", login, c.text()));
        log.info("GitHub app account: @{} (GITHUB_TOKEN) writes every comment, edit and reaction of the checker; {}",
            login, String.join("; ", checks.stream().map(AppAccount.Check::text).toList()));
    }

    /** Whether the app account may push to {@code repo}; null when GitHub does not say. */
    private Boolean canPush(String repo) {
        try {
            java.util.Map<?, ?> found = timed("appRepo", () -> appGet(props.apiUrl() + "/repos/" + repo)
                .body(java.util.Map.class), false);
            Object perms = found == null ? null : found.get("permissions");

            return perms instanceof java.util.Map<?, ?> m && m.get("push") instanceof Boolean push ? push : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * GitHub refused the app token on a call after the startup check: an expired or revoked GITHUB_TOKEN would
     * otherwise show as fine until the next restart, while every comment, edit and reaction of the checker fails.
     */
    private void appTokenRefused(String category) {
        if (props.token() == null || props.token().isBlank())
            return;

        AppAccount was = appAccount;
        if (AppAccount.REFUSED.equals(was.state()))
            return;

        appAccount = new AppAccount(AppAccount.REFUSED, was.login(), was.canPush(), was.id(), List.of());
        log.warn("GitHub refused GITHUB_TOKEN on a {} call: the checker writes nothing to GitHub until a call under it "
            + "works again", category);
    }

    /** A call under the app token worked: if it was refused, GitHub is asked again whom it belongs to. */
    private void appTokenWorked() {
        if (!AppAccount.REFUSED.equals(appAccount.state()) || !appAccountRecheck.compareAndSet(false, true))
            return;

        ownStatsFetch.execute(() -> {
            try {
                checkAppAccount();
            }
            finally {
                appAccountRecheck.set(false);
            }
        });
    }

    private final AtomicBoolean appAccountRecheck = new AtomicBoolean();

    private static final long APP_ACCOUNT_RECHECK_MS = 10 * 60_000L;

    private static final long APP_ACCOUNT_REFRESH_MS = 6 * 3600_000L;

    private volatile AppAccount appAccount = new AppAccount(AppAccount.UNKNOWN, null, null);

    private volatile long appAccountCheckedAt;

    /**
     * Whom {@code GITHUB_TOKEN} belongs to. {@code state}: "ok" with the {@code login} and {@code id} (and
     * {@code canPush}, whether it may push to the repo, null when unknown), "none" without a token, "refused" when
     * GitHub refuses it, "unknown" until GitHub has said. {@code checks}: what was found of the token and the account
     * when GitHub last said, see {@link AppAccountChecks}.
     */
    public record AppAccount(String state, String login, Boolean canPush, Long id, List<Check> checks) {
        static final String OK = "ok";

        static final String NONE = "none";

        static final String REFUSED = "refused";

        static final String UNKNOWN = "unknown";

        /** An account nothing was checked of. */
        public AppAccount(String state, String login, Boolean canPush) {
            this(state, login, canPush, null, List.of());
        }

        /**
         * What anyone may see: whether the account can push says how much a leaked server token is worth, and so do
         * the checks, which keep their levels only.
         */
        public AppAccount anonymous() {
            return new AppAccount(state, login, null, id,
                checks.stream().map(c -> new Check(c.level(), null)).toList());
        }

        /**
         * What a signed-in viewer who is not an operator may see: the day the token expires is for the operators, who
         * replace the token.
         */
        public AppAccount withoutDates() {
            return new AppAccount(state, login, canPush, id, checks.stream().map(Check::withoutDate).toList());
        }

        /** Whether GitHub refuses the token. */
        public boolean refused() {
            return REFUSED.equals(state);
        }

        /**
         * One thing found of the token or its account: {@code level} "ok", "unknown", "warn" or "error".
         * {@code dateless}: the text for viewers who are not operators, without the day the token expires; null when
         * {@code text} names no day.
         */
        public record Check(String level, String text, @JsonIgnore String dateless) {
            public static final String WARN = "warn";

            public static final String ERROR = "error";

            /** A check whose text names no day. */
            public Check(String level, String text) {
                this(level, text, null);
            }

            /** The check as a viewer who is not an operator reads it. */
            Check withoutDate() {
                return dateless == null ? this : new Check(level, dateless);
            }

            static Check ok(String text) {
                return new Check("ok", text);
            }

            static Check unknown(String text) {
                return new Check("unknown", text);
            }

            static Check warn(String text) {
                return new Check(WARN, text);
            }

            static Check error(String text) {
                return new Check(ERROR, text);
            }
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

    /**
     * What GitHub last said of the app token's hourly calls in the headers of its answers to them: how many are left,
     * of how many, and when its hour resets. Null until an answer said it.
     */
    public Quota appQuota() {
        return appQuota.get();
    }

    /** The app token's hourly calls: {@code remaining} of {@code limit} are left until {@code resetEpochSec}. */
    public record Quota(int remaining, int limit, long resetEpochSec) {
    }

    /** Notes what GitHub's answer to a call under the app token says of its hourly calls. */
    private ClientHttpResponse noteQuota(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (underAppToken(request))
            quotaOf(response.getHeaders()).ifPresent(q -> appQuota.accumulateAndGet(q, GithubClient::later));

        return response;
    }

    /** Whether a call goes under the app token, or under none without one: GitHub then counts calls by address. */
    private boolean underAppToken(HttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        return props.token() == null || props.token().isBlank() ? auth == null
            : ("Bearer " + props.token()).equals(auth);
    }

    /** The hourly calls the headers of an answer name; empty when they name none, or those of another API than core. */
    private static Optional<Quota> quotaOf(HttpHeaders headers) {
        String resource = headers.getFirst("X-RateLimit-Resource");
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        String limit = headers.getFirst("X-RateLimit-Limit");
        String reset = headers.getFirst("X-RateLimit-Reset");
        if (resource != null && !"core".equals(resource) || remaining == null || limit == null || reset == null)
            return Optional.empty();

        try {
            return Optional.of(new Quota(Integer.parseInt(remaining.strip()), Integer.parseInt(limit.strip()),
                Long.parseLong(reset.strip())));
        }
        catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The note of the later hour, and in one hour the one with fewer calls left: answers may come out of order. */
    private static Quota later(Quota known, Quota seen) {
        if (known == null || seen.resetEpochSec() > known.resetEpochSec())
            return seen;

        return seen.resetEpochSec() == known.resetEpochSec() && seen.remaining() < known.remaining() ? seen : known;
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
